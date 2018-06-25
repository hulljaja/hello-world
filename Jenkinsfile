pipeline {
            stage ('Deploy') {
                steps {
                  withCredentials([
                     file(credentialsId: 'mai-microservice', variable: 'keyfile')
			]) {
				sh "scp -i $keyfile -o StrictHostKeyChecking=no ${env.WORKSPACE}/target/*SNAPSHOT.jar ${env.USER_NAME}@${HOST_NAME}:/opt/mai/${config.serviceName}.jar"
			}
            }
        }
}
