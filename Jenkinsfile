pipeline {
            stage ('Deploy') {
                steps {
                  withCredentials([
                     file(credentialsId: 'mai-microservice', variable: 'keyfile')
			]) {
				sh "scp -i $keyfile -o StrictHostKeyChecking=no ${env.WORKSPACE}/hello-world ${env.USER_NAME}@10.1.2.41:/opt/mai/"
			}
            }
        }
}
