/*
test
*/

/* def config_folder_exists = fileExists 'config' */
def jobStartedByWhat() {
def startedByWhat = ''
   try {
         def buildCauses = currentBuild.rawBuild.getCauses()
      for ( buildCause in buildCauses ) {
         if (buildCause != null) {
           def causeDescription = buildCause.getShortDescription()
           echo "shortDescription: ${causeDescription}"
           if (causeDescription.contains("Started by timer")) {
              startedByWhat = 'timer'
           } else if (causeDescription.contains("Branch indexing")) {
              startedByWhat = 'BI'
           } else if (causeDescription.contains("Started by user")) {
              startedByWhat = 'user'
           } else if (causeDescription.contains('upstream project')) {
              startedByWhat = 'upstream'
              break
           } else {
              startedByWhat = 'unknown'
              }
           }
       }
   } catch(theError) {
      echo "Error getting build cause: ${theError}"
      }
   return startedByWhat
}
def jobrootname() {
	def thejobname = JOB_NAME.tokenize('/') as String[]
	def rootname = thejobname[0]
        return rootname
	}

def call(body) {

    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    pipeline {

        tools {
            maven 'Maven 3.5.2'
            jdk 'Java_1.8.0_144'
        }

        environment {
            SERVICE_NAME = "${config.serviceName}"
            HOST_NAME = ""
	    DeployHost = ""
            exitThisBuild = false
        }

        agent { label "${config.agentName}" }

parameters {
        string(defaultValue: "BRANCH_DEFINED", description: '', name: 'deploy_to')
    }



        triggers {
           upstream(upstreamProjects: 'mai-messaging', threshold: hudson.model.Result.SUCCESS)
        }


        stages {
            stage ('PreCheck') { 
             steps {
		script {
                  echo "parameter deploy_to is: ${env.deploy_to}"
		  def startedByWhat = jobStartedByWhat()	
		  if (env.BRANCH_NAME.contains("QA")) {
                     thisBranchName = 'QA'
                  } else if (env.BRANCH_NAME.contains("DEMO")){
                     thisBranchName = 'Demo'  
		  } else if (env.BRANCH_NAME.contains("develop")){
                     thisBranchName = 'DEV'
                  } else {
		   echo "Unknown branch, Exiting....."
                   sh "exit -1"
                   }
                  echo "paremeter deploy_to is: ${env.deploy_to}"
                  echo "started by ${env.BRANCH_NAME}"
		  env.exitThisBuild = "NO"
                  if ((startedByWhat == "BI" || startedByWhat == "upstream") && (thisBranchName == "QA" || thisBranchName == "DEMO")) {
		    echo "This is a ${thisBranchName} build and started by ${startedByWhat}.  Exiting"
	            echo "Exiting in Precheck"
   	            env.exitThisBuild = "YES"
                    currentBuild.result = 'ABORTED'
                    error('Exiting due to previously reported error')
		  } else if (startedByWhat == "unknown") {
                    env.exitThisBuild = "YES"
	     	    echo "This is a ${thisBranchName} Branch and started by ${startedByWhat}.  Build will exit...."
                    }
                  if (env.exitThisBuild == "YES") {
                    echo "Exiting build...."
                    currentBuild.result = 'ABORTED'
                    error('Exiting due to previously reported event')
		    sh exit
                    }
                  }
               }
             }

            stage ('Attach Head') {
                steps {
                        sh 'git branch -D $BRANCH_NAME || echo continuing...'
                        sh 'git checkout -b $BRANCH_NAME'

                    script {
                        if (env.BRANCH_NAME == "develop") {
                            HOST_NAME = env.DevelopAppServer
                        } else if (env.BRANCH_NAME.contains("QA")) {
                            HOST_NAME = env.QAAppServer
                        } else if (env.BRANCH_NAME.contains("DEMO")) {
                            HOST_NAME = env.DEMOAppServer
                        } else {
			    echo "Exiting build...."
                            currentBuild.result = 'FAILURE'
                    	    error("Exiting due to unknown branch: ${env.BRANCH_NAME}")
                            }
                    }
                }
            }

            stage ('Build') {
                steps {
                    configFileProvider([
                        configFile
                        (fileId: '61d79eb3-79b3-4302-91b5-4626c6c7e780', targetLocation: 'mavenSettings.txt')
                    ]) {}

                    sh 'mkdir -p ~/.m2; mv mavenSettings.txt ~/.m2/settings.xml'
                    sh 'pwd'
                    sh 'mvn -version'
                    sh 'mvn clean -U deploy'
                }
                post {
                    failure {
                        script { env.FAILURE_STAGE = 'Build' }
                    }
                }
            }

            stage('SonarQube analysis') {
                steps {
                  script {
                     if (env.BRANCH_NAME.contains("QA") || env.BRANCH_NAME.contains("DEMO")) {
                        return
                        }
                    // configured for use on MAI Jenkins server
                    withSonarQubeEnv('SonarQube') {
                        sh '/etc/sonar-scanner-3.0.3.778-linux/bin/sonar-scanner'
                    }
                     }
                }
            }

            stage ('Deploy') {
                steps {
                  withCredentials([
                     file(credentialsId: 'mai-microservice', variable: 'keyfile')
			]) {
				sh "scp -i $keyfile -o StrictHostKeyChecking=no ${env.WORKSPACE}/target/*SNAPSHOT.jar ${env.USER_NAME}@${HOST_NAME}:/opt/mai/${config.serviceName}.jar"
			  script {
 				def config_folder_exists = fileExists 'config'    
				if( config_folder_exists ) {
					sh "scp -i $keyfile -o StrictHostKeyChecking=no ${env.WORKSPACE}/config/* ${env.USER_NAME}@${HOST_NAME}:/opt/mai/config/"
					}
			  }
			}

                  script {
                     if (env.BRANCH_NAME.contains("QA") || env.BRANCH_NAME.contains("DEMO")) {
                        return
                        }

                  withCredentials([
                     file(credentialsId: 'mai-microservice', variable: 'keyfile')
                  ]) { sh "ssh -i $keyfile -o StrictHostKeyChecking=no ${env.USER_NAME}@${HOST_NAME} '/opt/mai/script/start-service.sh ${config.serviceName}'"}
                     } 

            }
            post {
                failure {
                    script { env.FAILURE_STAGE = 'Build' }
                }
            }
        }

        }
    post {
        failure {
		script {
			def jj = jobrootname()
			echo "the name: $jj"
                	def summary = "FAILED: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]' has failed in the *$env.FAILURE_STAGE* stage. (${env.JENKINS_DNS_URL}/job/$jj/job/${env.JOB_BASE_NAME}/${env.BUILD_NUMBER}/console)"
                        echo "summary: $summary"
            slackSend (
                color: '#FF0000',
/*                message: "FAILED: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]' has failed in the *$env.FAILURE_STAGE* stage. (${env.BUILD_URL})" */
/*                message: "FAILED: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]' has failed in the *$env.FAILURE_STAGE* stage. (${env.JENKINS_DNS_URL}/job/${jobrootname}/job/${env.JOB_BASE_NAME}/${env.BUILD_NUMBER})"  */
                message: summary
            )

            emailext (
                attachLog: true,
                compressLog: true,
                subject: "FAILED: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]'",
                mimeType: "text/html",
                body: """<p>FAILED: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]' in the <strong>${env.FAILURE_STAGE}</strong> stage:</p>
                    <p>Check console output at <a href='${env.BUILD_URL}'>${env.JOB_NAME} [${env.BUILD_NUMBER}]</a> and/or see attached log</p>""",
                recipientProviders: [[$class: 'DevelopersRecipientProvider'], [$class: 'RequesterRecipientProvider']],
                to: "mai-devs@bericotechnologies.com"
            )
        }
	}
        fixed {
            script {
	            if (!hudson.model.Result.SUCCESS.equals(currentBuild.getPreviousBuild()?.getResult())  &&
	                hudson.model.Result.SUCCESS.equals(currentBuild.getResult())) {
		            slackSend (
		                color: '#00FF00',
		                message: "FIXED: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]' successfully built and was previously failing. See (${env.BUILD_URL}) if you're curious."
		            )
		
		            emailext (
		                subject: "FIXED: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]' successfully built and was previously failing.",
		                mimeType: "text/html",
		                body: """<p>FIXED: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]':</p>
		                    <p>Check console output at <a href='${env.BUILD_URL}'>${env.JOB_NAME} [${env.BUILD_NUMBER}]</a></p> if you're curious.""",
		                recipientProviders: [[$class: 'DevelopersRecipientProvider'], [$class: 'RequesterRecipientProvider']],
		                to: "mai-devs@bericotechnologies.com"
		            )
		        }
		    }
        	}
    	}
    }
}
     

