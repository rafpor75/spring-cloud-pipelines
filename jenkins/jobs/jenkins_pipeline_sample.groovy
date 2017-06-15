import javaposse.jobdsl.dsl.DslFactory
import javaposse.jobdsl.dsl.helpers.BuildParametersContext

DslFactory dsl = this

// These will be taken either from seed or global variables
PipelineDefaults defaults = new PipelineDefaults(binding.variables)

// Example of a version with date and time in the name
String pipelineVersion = binding.variables["PIPELINE_VERSION"] ?: '''1.0.0.M1-${GROOVY,script ="new Date().format('yyMMdd_HHmmss')"}-VERSION'''
String cronValue = "H H * * 7" //every Sunday - I guess you should run it more often ;)
String testReports = ["**/surefire-reports/*.xml", "**/test-results/**/*.xml"].join(",")
String gitCredentials = binding.variables["GIT_CREDENTIAL_ID"] ?: "git"
String repoWithJarsCredentials = binding.variables["REPO_WITH_JARS_CREDENTIALS_ID"] ?: "repo-with-jars"
String jdkVersion = binding.variables["JDK_VERSION"] ?: "jdk8"
String cfTestCredentialId = binding.variables["CF_TEST_CREDENTIAL_ID"] ?: "cf-test"
String cfStageCredentialId = binding.variables["CF_STAGE_CREDENTIAL_ID"] ?: "cf-stage"
String cfProdCredentialId = binding.variables["CF_PROD_CREDENTIAL_ID"] ?: "cf-prod"
String gitEmail = binding.variables["GIT_EMAIL"] ?: "pivo@tal.com"
String gitName = binding.variables["GIT_NAME"] ?: "Pivo Tal"
boolean autoStage = binding.variables["AUTO_DEPLOY_TO_STAGE"] == null ? false : Boolean.parseBoolean(binding.variables["AUTO_DEPLOY_TO_STAGE"])
boolean autoProd = binding.variables["AUTO_DEPLOY_TO_PROD"] == null ? false : Boolean.parseBoolean(binding.variables["AUTO_DEPLOY_TO_PROD"])
boolean rollbackStep = binding.variables["ROLLBACK_STEP_REQUIRED"] == null ? true : Boolean.parseBoolean(binding.variables["ROLLBACK_STEP_REQUIRED"])
boolean stageStep = binding.variables["DEPLOY_TO_STAGE_STEP_REQUIRED"] == null ? true : Boolean.parseBoolean(binding.variables["DEPLOY_TO_STAGE_STEP_REQUIRED"])
String scriptsDir = binding.variables["SCRIPTS_DIR"] ?: "${WORKSPACE}/common/src/main/bash"

// we're parsing the REPOS parameter to retrieve list of repos to build
String repos = binding.variables["REPOS"] ?:
		["https://github.com/marcingrzejszczak/github-analytics",
		 "https://github.com/marcingrzejszczak/github-webhook"].join(",")
List<String> parsedRepos = repos.split(",")
parsedRepos.each {
	List<String> parsedEntry = it.split('\\$')
	String gitRepoName
	String fullGitRepo
	if (parsedEntry.size() > 1) {
		gitRepoName = parsedEntry[0]
		fullGitRepo = parsedEntry[1]
	} else {
		gitRepoName = parsedEntry[0].split('/').last()
		fullGitRepo = parsedEntry[0]
	}
	String projectName = "${gitRepoName}-pipeline"

	//  ======= JOBS =======
	dsl.job("${projectName}-build") {
		deliveryPipelineConfiguration('Build', 'Build and Upload')
		triggers {
			cron(cronValue)
			githubPush()
		}
		wrappers {
			deliveryPipelineVersion(pipelineVersion, true)
			environmentVariables {
				environmentVariables(defaults.defaultEnvVars)
				groovy(PipelineDefaults.groovyEnvScript)
			}
			parameters(PipelineDefaults.defaultParams())
			timestamps()
			colorizeOutput()
			maskPasswords()
			timeout {
				noActivity(300)
				failBuild()
				writeDescription('Build failed due to timeout after {0} minutes of inactivity')
			}
			credentialsBinding {
				usernamePassword('M2_SETTINGS_REPO_USERNAME', 'M2_SETTINGS_REPO_PASSWORD', repoWithJarsCredentials)
			}
		}
		jdk(jdkVersion)
		scm {
			git {
				remote {
					name('origin')
					url(fullGitRepo)
					branch('master')
					credentials(gitCredentials)
				}
				extensions {
					wipeOutWorkspace()
				}
			}
		}
		configure { def project ->
			// Adding user email and name here instead of global settings
			project / 'scm' / 'extensions' << 'hudson.plugins.git.extensions.impl.UserIdentity' {
				'email'(gitEmail)
				'name'(gitName)
			}
		}
		steps {
			shell("""#!/bin/bash
		set -e

		${dsl.readFileFromWorkspace(scriptsDir + '/pipeline.sh')}
		${dsl.readFileFromWorkspace(scriptsDir + '/build_and_upload.sh')}
		""")
		}
		publishers {
			archiveJunit(testReports)
			downstreamParameterized {
				trigger("${projectName}-build-api-check") {
					triggerWithNoParameters()
					parameters {
						currentBuild()
					}
				}
			}
			git {
				pushOnlyIfSuccess()
				tag('origin', "dev/\${PIPELINE_VERSION}") {
					create()
					update()
				}
			}
		}
	}

	dsl.job("${projectName}-build-api-check") {
		deliveryPipelineConfiguration('Build', 'API compatibility check')
		triggers {
			cron(cronValue)
			githubPush()
		}
		wrappers {
			deliveryPipelineVersion('${ENV,var="PIPELINE_VERSION"}', true)
			environmentVariables {
				environmentVariables(defaults.defaultEnvVars)
				groovy(PipelineDefaults.groovyEnvScript)
			}
			parameters(PipelineDefaults.defaultParams())
			timestamps()
			colorizeOutput()
			maskPasswords()
			timeout {
				noActivity(300)
				failBuild()
				writeDescription('Build failed due to timeout after {0} minutes of inactivity')
			}
		}
		jdk(jdkVersion)
		scm {
			git {
				remote {
					name('origin')
					url(fullGitRepo)
					branch('master')
					credentials(gitCredentials)
				}
				extensions {
					wipeOutWorkspace()
				}
			}
		}
		steps {
			shell("""#!/bin/bash
		set -e

		${dsl.readFileFromWorkspace(scriptsDir + '/pipeline.sh')}
		${dsl.readFileFromWorkspace(scriptsDir + '/build_api_compatibility_check.sh')}
		""")
		}
		publishers {
			archiveJunit(testReports) {
				allowEmptyResults()
			}
			downstreamParameterized {
				trigger("${projectName}-test-env-deploy") {
					triggerWithNoParameters()
					parameters {
						currentBuild()
					}
				}
			}
		}
	}

	dsl.job("${projectName}-test-env-deploy") {
		deliveryPipelineConfiguration('Test', 'Deploy to test')
		wrappers {
			deliveryPipelineVersion('${ENV,var="PIPELINE_VERSION"}', true)
			parameters(PipelineDefaults.defaultParams())
			environmentVariables {
				environmentVariables(defaults.defaultEnvVars)
				groovy(PipelineDefaults.groovyEnvScript)
			}
			credentialsBinding {
				usernamePassword('CF_TEST_USERNAME', 'CF_TEST_PASSWORD', cfTestCredentialId)
			}
			timestamps()
			colorizeOutput()
			maskPasswords()
			timeout {
				noActivity(300)
				failBuild()
				writeDescription('Build failed due to timeout after {0} minutes of inactivity')
			}
		}
		scm {
			git {
				remote {
					url(fullGitRepo)
					branch('dev/${PIPELINE_VERSION}')
				}
			}
		}
		steps {
			shell("""#!/bin/bash
		set -e

		${dsl.readFileFromWorkspace(scriptsDir + '/pipeline.sh')}
		${dsl.readFileFromWorkspace(scriptsDir + '/test_deploy.sh')}
		""")
		}
		publishers {
			downstreamParameterized {
				trigger("${projectName}-test-env-test") {
					parameters {
						propertiesFile('target/test.properties,build/test.properties', true)
						currentBuild()
					}
					triggerWithNoParameters()
				}
			}
		}
	}

	dsl.job("${projectName}-test-env-test") {
		deliveryPipelineConfiguration('Test', 'Tests on test')
		wrappers {
			deliveryPipelineVersion('${ENV,var="PIPELINE_VERSION"}', true)
			parameters(PipelineDefaults.defaultParams())
			parameters PipelineDefaults.smokeTestParams()
			environmentVariables {
				environmentVariables(defaults.defaultEnvVars)
				groovy(PipelineDefaults.groovyEnvScript)
			}
			credentialsBinding {
				usernamePassword('CF_TEST_USERNAME', 'CF_TEST_PASSWORD', cfTestCredentialId)
			}
			timestamps()
			colorizeOutput()
			maskPasswords()
			timeout {
				noActivity(300)
				failBuild()
				writeDescription('Build failed due to timeout after {0} minutes of inactivity')
			}
		}
		scm {
			git {
				remote {
					url(fullGitRepo)
					branch('dev/${PIPELINE_VERSION}')
				}
				extensions {
					wipeOutWorkspace()
				}
			}
		}
		steps {
			shell("""#!/bin/bash
		set -e

		${dsl.readFileFromWorkspace(scriptsDir + '/pipeline.sh')}
		${dsl.readFileFromWorkspace(scriptsDir + '/test_smoke.sh')}
		""")
		}
		publishers {
			archiveJunit(testReports)
			if (rollbackStep) {
				downstreamParameterized {
					trigger("${projectName}-test-env-rollback-deploy") {
						parameters {
							currentBuild()
						}
						triggerWithNoParameters()
					}
				}
			} else {
				String stepName = stageStep ? "stage" : "prod"
				downstreamParameterized {
					trigger("${projectName}-${stepName}-env-deploy") {
						parameters {
							currentBuild()
						}
						triggerWithNoParameters()
					}
				}
			}
		}
	}

	if (rollbackStep) {
		dsl.job("${projectName}-test-env-rollback-deploy") {
			deliveryPipelineConfiguration('Test', 'Deploy to test latest prod version')
			wrappers {
				deliveryPipelineVersion('${ENV,var="PIPELINE_VERSION"}', true)
				parameters(PipelineDefaults.defaultParams())
				environmentVariables {
					environmentVariables(defaults.defaultEnvVars)
					groovy(PipelineDefaults.groovyEnvScript)
				}
				credentialsBinding {
					usernamePassword('CF_TEST_USERNAME', 'CF_TEST_PASSWORD', cfTestCredentialId)
				}
				timeout {
					noActivity(300)
					failBuild()
					writeDescription('Build failed due to timeout after {0} minutes of inactivity')
				}
			}
			scm {
				git {
					remote {
						url(fullGitRepo)
						branch('dev/${PIPELINE_VERSION}')
					}
					extensions {
						wipeOutWorkspace()
					}
				}
			}
			steps {
				shell("""#!/bin/bash
		set -e

		${dsl.readFileFromWorkspace(scriptsDir + '/pipeline.sh')}
		${dsl.readFileFromWorkspace(scriptsDir + '/test_rollback_deploy.sh')}
		""")
			}
			publishers {
				downstreamParameterized {
					trigger("${projectName}-test-env-rollback-test") {
						triggerWithNoParameters()
						parameters {
							propertiesFile('target/test.properties,build/test.properties', false)
							currentBuild()
						}
					}
				}
			}
		}

		dsl.job("${projectName}-test-env-rollback-test") {
			deliveryPipelineConfiguration('Test', 'Tests on test latest prod version')
			wrappers {
				deliveryPipelineVersion('${ENV,var="PIPELINE_VERSION"}', true)
				parameters(PipelineDefaults.defaultParams())
				parameters PipelineDefaults.smokeTestParams()
				environmentVariables {
					environmentVariables(defaults.defaultEnvVars)
					groovy(PipelineDefaults.groovyEnvScript)
				}
				credentialsBinding {
					usernamePassword('CF_TEST_USERNAME', 'CF_TEST_PASSWORD', cfTestCredentialId)
				}
				parameters {
					stringParam('LATEST_PROD_TAG', 'master', 'Latest production tag. If "master" is picked then the step will be ignored')
				}
				timestamps()
				colorizeOutput()
				maskPasswords()
				timeout {
					noActivity(300)
					failBuild()
					writeDescription('Build failed due to timeout after {0} minutes of inactivity')
				}
			}
			scm {
				git {
					remote {
						url(fullGitRepo)
						branch('${LATEST_PROD_TAG}')
					}
					extensions {
						wipeOutWorkspace()
					}
				}
			}
			steps {
				shell("""#!/bin/bash
		set -e

		${dsl.readFileFromWorkspace(scriptsDir + '/pipeline.sh')}
		${dsl.readFileFromWorkspace(scriptsDir + '/test_rollback_smoke.sh')}
		""")
			}
			publishers {
				archiveJunit(testReports) {
					allowEmptyResults()
				}
				if(stageStep) {
					String nextJob = "${projectName}-stage-env-deploy"
					if (autoStage) {
						downstreamParameterized {
							trigger(nextJob) {
								parameters {
									currentBuild()
								}
							}
						}
					} else {
						buildPipelineTrigger(nextJob) {
							parameters {
								currentBuild()
							}
						}
					}
				} else {
						String nextJob = "${projectName}-prod-env-deploy"
						if (autoProd) {
							downstreamParameterized {
								trigger(nextJob) {
									parameters {
										currentBuild()
									}
								}
							}
						} else {
							buildPipelineTrigger(nextJob) {
								parameters {
									currentBuild()
								}
							}
						}
				}
			}
		}
	}

	if (stageStep) {
		dsl.job("${projectName}-stage-env-deploy") {
			deliveryPipelineConfiguration('Stage', 'Deploy to stage')
			wrappers {
				deliveryPipelineVersion('${ENV,var="PIPELINE_VERSION"}', true)
				maskPasswords()
				parameters(PipelineDefaults.defaultParams())
				environmentVariables {
					environmentVariables(defaults.defaultEnvVars)
					groovy(PipelineDefaults.groovyEnvScript)
				}
				credentialsBinding {
					usernamePassword('CF_STAGE_USERNAME', 'CF_STAGE_PASSWORD', cfStageCredentialId)
				}
				timestamps()
				colorizeOutput()
				maskPasswords()
				timeout {
					noActivity(300)
					failBuild()
					writeDescription('Build failed due to timeout after {0} minutes of inactivity')
				}
			}
			scm {
				git {
					remote {
						url(fullGitRepo)
						branch('dev/${PIPELINE_VERSION}')
					}
				}
			}
			steps {
				shell("""#!/bin/bash
			set -e

			${dsl.readFileFromWorkspace(scriptsDir + '/pipeline.sh')}
			${dsl.readFileFromWorkspace(scriptsDir + '/stage_deploy.sh')}
			""")
			}
			publishers {
				if (autoStage) {
					downstreamParameterized {
						trigger("${projectName}-stage-env-test") {
							triggerWithNoParameters()
							parameters {
								currentBuild()
								propertiesFile('${OUTPUT_FOLDER}/test.properties', true)
							}
						}
					}
				} else {
					buildPipelineTrigger("${projectName}-stage-env-test") {
						parameters {
							currentBuild()
							propertiesFile('target/test.properties,build/test.properties', true)
						}
					}
				}
			}
		}

		dsl.job("${projectName}-stage-env-test") {
			deliveryPipelineConfiguration('Stage', 'End to end tests on stage')
			wrappers {
				deliveryPipelineVersion('${ENV,var="PIPELINE_VERSION"}', true)
				parameters(PipelineDefaults.defaultParams())
				parameters PipelineDefaults.smokeTestParams()
				environmentVariables {
					environmentVariables(defaults.defaultEnvVars)
					groovy(PipelineDefaults.groovyEnvScript)
				}
				credentialsBinding {
					usernamePassword('CF_STAGE_USERNAME', 'CF_STAGE_PASSWORD', cfStageCredentialId)
				}
				timestamps()
				colorizeOutput()
				maskPasswords()
				timeout {
					noActivity(300)
					failBuild()
					writeDescription('Build failed due to timeout after {0} minutes of inactivity')
				}
			}
			scm {
				git {
					remote {
						url(fullGitRepo)
						branch('dev/${PIPELINE_VERSION}')
					}
					extensions {
						wipeOutWorkspace()
					}
				}
			}
			steps {
				shell("""#!/bin/bash
			set -e

			${dsl.readFileFromWorkspace(scriptsDir + '/pipeline.sh')}
			${dsl.readFileFromWorkspace(scriptsDir + '/stage_e2e.sh')}
			""")
			}
			publishers {
				archiveJunit(testReports)
				String nextJob = "${projectName}-prod-env-deploy"
				if (autoProd) {
					downstreamParameterized {
						trigger(nextJob) {
							parameters {
								currentBuild()
							}
						}
					}
				} else {
					buildPipelineTrigger(nextJob) {
						parameters {
							currentBuild()
						}
					}
				}
			}
		}
	}

	dsl.job("${projectName}-prod-env-deploy") {
		deliveryPipelineConfiguration('Prod', 'Deploy to prod')
		wrappers {
			deliveryPipelineVersion('${ENV,var="PIPELINE_VERSION"}', true)
			maskPasswords()
			parameters(PipelineDefaults.defaultParams())
			environmentVariables {
				environmentVariables(defaults.defaultEnvVars)
				groovy(PipelineDefaults.groovyEnvScript)
			}
			credentialsBinding {
				usernamePassword('CF_PROD_USERNAME', 'CF_PROD_PASSWORD', cfProdCredentialId)
			}
			timestamps()
			colorizeOutput()
			maskPasswords()
			timeout {
				noActivity(300)
				failBuild()
				writeDescription('Build failed due to timeout after {0} minutes of inactivity')
			}
		}
		scm {
			git {
				remote {
					name('origin')
					url(fullGitRepo)
					branch('dev/${PIPELINE_VERSION}')
					credentials(gitCredentials)
				}
			}
		}
		configure { def project ->
			// Adding user email and name here instead of global settings
			project / 'scm' / 'extensions' << 'hudson.plugins.git.extensions.impl.UserIdentity' {
				'email'(gitEmail)
				'name'(gitName)
			}
		}
		steps {
			shell("""#!/bin/bash
		set -e

		${dsl.readFileFromWorkspace(scriptsDir + '/pipeline.sh')}
		${dsl.readFileFromWorkspace(scriptsDir + '/prod_deploy.sh')}
		""")
		}
		publishers {
			buildPipelineTrigger("${projectName}-prod-env-complete") {
				parameters {
					currentBuild()
				}
			}
			git {
				forcePush(true)
				pushOnlyIfSuccess()
				tag('origin', "prod/\${PIPELINE_VERSION}") {
					create()
					update()
				}
			}
		}
	}

	dsl.job("${projectName}-prod-env-complete") {
		deliveryPipelineConfiguration('Prod', 'Complete switch over')
		wrappers {
			deliveryPipelineVersion('${ENV,var="PIPELINE_VERSION"}', true)
			maskPasswords()
			parameters(PipelineDefaults.defaultParams())
			environmentVariables {
				environmentVariables(defaults.defaultEnvVars)
				groovy(PipelineDefaults.groovyEnvScript)
			}
			credentialsBinding {
				usernamePassword('CF_PROD_USERNAME', 'CF_PROD_PASSWORD', cfProdCredentialId)
			}
			timestamps()
			colorizeOutput()
			maskPasswords()
			timeout {
				noActivity(300)
				failBuild()
				writeDescription('Build failed due to timeout after {0} minutes of inactivity')
			}
		}
		scm {
			git {
				remote {
					name('origin')
					url(fullGitRepo)
					branch('dev/${PIPELINE_VERSION}')
					credentials(gitCredentials)
				}
			}
		}
		steps {
			shell("""#!/bin/bash
			set - e

			${dsl.readFileFromWorkspace(scriptsDir + '/pipeline.sh') }
			${dsl.readFileFromWorkspace(scriptsDir + '/prod_complete.sh') }
		""")
		}
	}
}

//  ======= JOBS =======

/**
 * A helper class to provide delegation for Closures. That way your IDE will help you in defining parameters.
 * Also it contains the default env vars setting
 */
class PipelineDefaults {

	final Map<String, String> defaultEnvVars

	PipelineDefaults(Map<String, String> variables) {
		this.defaultEnvVars = defaultEnvVars(variables)
	}

	private Map<String, String> defaultEnvVars(Map<String, String> variables) {
		Map<String, String> envs = [:]
		envs['CF_TEST_API_URL'] = variables['CF_TEST_API_URL'] ?: 'api.local.pcfdev.io'
		envs['CF_STAGE_API_URL'] = variables['CF_STAGE_API_URL'] ?: 'api.local.pcfdev.io'
		envs['CF_PROD_API_URL'] = variables['CF_PROD_API_URL'] ?: 'api.local.pcfdev.io'
		envs['CF_TEST_ORG'] = variables['CF_TEST_ORG'] ?: 'pcfdev-org'
		envs['CF_TEST_SPACE'] = variables['CF_TEST_SPACE'] ?: 'pfcdev-test'
		envs['CF_STAGE_ORG'] = variables['CF_STAGE_ORG'] ?: 'pcfdev-org'
		envs['CF_STAGE_SPACE'] = variables['CF_STAGE_SPACE'] ?: 'pfcdev-stage'
		envs['CF_PROD_ORG'] = variables['CF_PROD_ORG'] ?: 'pcfdev-org'
		envs['CF_PROD_SPACE'] = variables['CF_PROD_SPACE'] ?: 'pfcdev-prod'
		envs['CF_HOSTNAME_UUID'] = variables['CF_HOSTNAME_UUID'] ?: ''
		envs['M2_SETTINGS_REPO_ID'] = variables['M2_SETTINGS_REPO_ID'] ?: 'artifactory-local'
		envs['REPO_WITH_JARS'] = variables['REPO_WITH_JARS'] ?: 'http://artifactory:8081/artifactory/libs-release-local'
		envs['APP_MEMORY_LIMIT'] = variables['APP_MEMORY_LIMIT'] ?: '256m'
		envs['JAVA_BUILDPACK_URL'] = variables['JAVA_BUILDPACK_URL'] ?: 'https://github.com/cloudfoundry/java-buildpack.git#v3.8.1'
		return envs
	}

	public static final String groovyEnvScript = '''
String workspace = binding.variables['WORKSPACE']
String mvn = "${workspace}/mvnw"
String gradle =  "${workspace}/gradlew"

Map envs = [:]
if (new File(mvn).exists()) {
	envs['PROJECT_TYPE'] = "MAVEN"
	envs['OUTPUT_FOLDER'] = "target"
} else if (new File(gradle).exists()) {
	envs['PROJECT_TYPE'] = "GRADLE"
	envs['OUTPUT_FOLDER'] = "build/libs"
}
return envs'''

	protected static Closure context(@DelegatesTo(BuildParametersContext) Closure params) {
		params.resolveStrategy = Closure.DELEGATE_FIRST
		return params
	}

	/**
	 * With the Security constraints in Jenkins in order to pass the parameters between jobs, every job
	 * has to define the parameters on input. In order not to copy paste the params we're doing this
	 * default params method.
	 */
	static Closure defaultParams() {
		return context {
			booleanParam('REDOWNLOAD_INFRA', false, "If Eureka & StubRunner & CF binaries should be redownloaded if already present")
			booleanParam('REDEPLOY_INFRA', true, "If Eureka JAR should be deployed. Uncheck this if you're not using Eureka")
			stringParam('EUREKA_GROUP_ID', 'com.example.eureka', "Group Id for Eureka used by tests")
			stringParam('EUREKA_ARTIFACT_ID', 'github-eureka', "Artifact Id for Eureka used by tests")
			stringParam('EUREKA_VERSION', '0.0.1.M1', "Artifact Version for Eureka used by tests")
			stringParam('STUBRUNNER_GROUP_ID', 'com.example.github', "Group Id for Stub Runner used by tests")
			stringParam('STUBRUNNER_ARTIFACT_ID', 'github-analytics-stub-runner-boot', "Artifact Id for Stub Runner used by tests")
			stringParam('STUBRUNNER_VERSION', '0.0.1.M1', "Artifact Version for Stub Runner used by tests")
			booleanParam('STUBRUNNER_USE_CLASSPATH', false, "Should Stub Runner use classpath instead of reaching a repo")
			stringParam('BUILD_OPTIONS', null, "Additional build options to be passed to the build tool")
		}
	}

	/**
	 * With the Security constraints in Jenkins in order to pass the parameters between jobs, every job
	 * has to define the parameters on input. We provide additional smoke tests parameters.
	 */
	static Closure smokeTestParams() {
		return context {
			stringParam('APPLICATION_URL', '', "URL of the deployed application")
			stringParam('STUBRUNNER_URL', '', "URL of the deployed stub runner application")
		}
	}
}
