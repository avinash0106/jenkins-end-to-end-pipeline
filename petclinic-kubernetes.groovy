// https://plugins.jenkins.io/docker-workflow/
// XGwscKDIs7RrQGYcTJSuMf
// f8251776-04d2-4eac-8ca2-8f51eb25d961

pipeline {
    agent {
      kubernetes {
        yaml '''
          apiVersion: v1
          kind: Pod
          metadata:
            labels:
              app: test
          spec:
            containers:
            - name: maven
              image: maven:3.8.3-adoptopenjdk-11
              command:
              - cat
              tty: true
              volumeMounts:
                - mountPath: "/root/.m2/repository"
                  name: maven-cache
            - name: docker
              image: docker:latest
              command:
              - cat
              tty: true
              volumeMounts:
              - mountPath: /var/run/docker.sock
                name: docker-sock
            - name: sonarcli
              image: sonarsource/sonar-scanner-cli
              command:
              - cat
              tty: true
            - name: git
              image: bitnami/git:latest
              command:
              - cat
              tty: true
            - name: helm
              image: kunchalavikram/kubectl_helm_cli:latest
              command:
              - cat
              tty: true
            volumes:
            - name: maven-cache
              persistentVolumeClaim:
                claimName: maven-cache
            - name: docker-sock
              hostPath:
                path: /var/run/docker.sock
          '''
      }
    }
    options {
        buildDiscarder logRotator(daysToKeepStr: '2', numToKeepStr: '10')
        timeout(time: 10, unit: 'MINUTES')
    }
    environment {
        DOCKERHUB_USERNAME = "kunchalavikram"
        APP_NAME = "spring-petclinic"
        IMAGE_NAME = "${DOCKERHUB_USERNAME}" + "/" + "${APP_NAME}" // String concatenation
        IMAGE_TAG = "${BUILD_NUMBER}"
        NEXUS_VERSION = "nexus3"
        NEXUS_PROTOCOL = "http"
        NEXUS_URL = "167.99.18.228:8081"
        NEXUS_REPOSITORY = "maven-hosted"
        NEXUS_CREDENTIAL_ID = "nexus-creds"
    }
    stages {
        stage('Checkout SCM'){
            when {
                expression { true }
            }
            steps {
              container('git') {
                  git url: 'https://github.com/kunchalavikram1427/spring-petclinic.git',
                  branch: 'main'
              }
            }
        }
        stage('Code Build') {
            when {
                expression { true }
            }
            steps {
                container('maven') {
                    sh "mvn -Dmaven.test.failure.ignore=true clean package"
                }
            }
            post {
                success {
                    junit '**/target/surefire-reports/*.xml'
                }
            }
        }
        stage('Build Docker Image'){
            when {
                expression { true }
            }
            steps {
                container('docker') {
                    sh "docker build -t $IMAGE_NAME:$IMAGE_TAG ."
                    sh "docker tag $IMAGE_NAME:$IMAGE_TAG $IMAGE_NAME:latest"
                    withCredentials([usernamePassword(credentialsId: 'docker', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                        sh "docker login -u $USER -p $PASS"
                        sh "docker push $IMAGE_NAME:$IMAGE_TAG"
                        sh "docker push $IMAGE_NAME:latest"
                    }
                    sh "docker rmi ${IMAGE_NAME}:${IMAGE_TAG}"
                    sh "docker rmi ${IMAGE_NAME}:latest"
                }
            }
        }
        stage("Publish to nexus") {
            when {
                expression { true }
            }
            steps {
                container('jnlp') {
                    script {
                        pom = readMavenPom file: "pom.xml";
                        filesByGlob = findFiles(glob: "target/*.${pom.packaging}"); 
                        echo "${filesByGlob[0].name} ${filesByGlob[0].path} ${filesByGlob[0].directory} ${filesByGlob[0].length} ${filesByGlob[0].lastModified}"
                        artifactPath = filesByGlob[0].path;
                        artifactExists = fileExists artifactPath;
                        if(artifactExists) {
                            echo "*** File: ${artifactPath}, group: ${pom.groupId}, packaging: ${pom.packaging}, version ${pom.version}";
                            nexusArtifactUploader(
                                nexusVersion: NEXUS_VERSION,
                                protocol: NEXUS_PROTOCOL,
                                nexusUrl: NEXUS_URL,
                                groupId: pom.groupId,
                                version: pom.version,
                                repository: NEXUS_REPOSITORY,
                                credentialsId: NEXUS_CREDENTIAL_ID,
                                artifacts: [
                                    [artifactId: pom.artifactId,
                                    classifier: '',
                                    file: artifactPath,
                                    type: pom.packaging],

                                    [artifactId: pom.artifactId,
                                    classifier: '',
                                    file: "pom.xml",
                                    type: "pom"]
                                ]
                            );

                        } else {
                            error "*** File: ${artifactPath}, could not be found";
                        }
                    }
                }
            }
        }
        stage('SonarScan'){
            when {
                expression { true }
            }
            steps {
                container('sonarcli') {  // /opt/sonar-scanner/bin/sonar-scanner
                    withSonarQubeEnv(credentialsId: 'sonar', installationName: 'sonarserver') { //Server Info
                        sh '''/opt/sonar-scanner/bin/sonar-scanner \
                          -Dsonar.projectKey=petclinic \
                          -Dsonar.projectName=petclinic \
                          -Dsonar.projectVersion=1.0 \
                          -Dsonar.sources=src/main \
                          -Dsonar.tests=src/test \
                          -Dsonar.java.binaries=target/classes  \
                          -Dsonar.language=java \
                          -Dsonar.sourceEncoding=UTF-8 \
                          -Dsonar.java.libraries=target/classes
                          '''
                    }
                }
            }
        }
        stage('Wait for QG'){
            when {
                expression { true }
            }
            steps {
                container('sonarcli') {  
                    timeout(time: 1, unit: 'MINUTES') {
                       waitForQualityGate abortPipeline: true
                    }
                }
            }
        }
        stage('App Deployment'){
            when {
                expression { true }
            }
            steps {
                container('helm') {  
                    withKubeConfig(caCertificate: '', clusterName: '', contextName: '', credentialsId: 'k8s', namespace: '', serverUrl: '') {
                        sh "helm upgrade --install petclinic petclinic-chart/"
                    }
                }
            }
        }
    } 
}