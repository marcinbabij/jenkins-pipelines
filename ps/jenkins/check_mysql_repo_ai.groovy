library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

// Separate from the tag job so a no-AI CSV is archived before any model
// call. Re-run this job to redo the AI column without moving S3 state.

def GitHubURL = 'https://github.com'

pipeline {
    agent {
        label 'docker'
    }
    parameters {
        string(
            name: 'SOURCE_JOB',
            defaultValue: 'check-mysql-repo',
            description: 'Job that archived csv_output/*_no_ai.csv. Blank is not allowed.'
        )
        choice(
            name: 'MODEL',
            choices: ['opus', 'sonnet', 'haiku', 'fable', 'best', 'opusplan', 'opus[1m]', 'sonnet[1m]'],
            description: 'claude --model alias. opus is the latest Opus for the provider. For Bedrock, enable USE_BEDROCK; the alias still selects the provider default for that family.'
        )
        choice(
            name: 'EFFORT',
            choices: ['low', 'medium', 'high', 'xhigh', 'max'],
            description: 'claude --effort. low is the default.'
        )
        booleanParam(
            name: 'USE_BEDROCK',
            defaultValue: false,
            description: 'Set CLAUDE_CODE_USE_BEDROCK=1 for this run. The agent still needs AWS credentials that can call Bedrock.'
        )
    }
    options {
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
        timestamps()
    }
    stages {
        stage('Fetch report') {
            steps {
                copyArtifacts(
                    projectName: params.SOURCE_JOB.trim(),
                    selector: lastSuccessful(),
                    filter: 'csv_output/*_no_ai.csv',
                    flatten: true,
                    optional: false,
                    fingerprintArtifacts: false
                )
            }
        }
        stage('Clone MySQL') {
            steps {
                // Public repo. Blobs are fetched later, when git show reads
                // a commit. The commit graph is still required.
                sh """
                    set -o errexit
                    set -o xtrace
                    git clone --filter=blob:none ${GitHubURL}/mysql/mysql-server mysql-server-repo
                """
            }
        }
        stage('Fetch script') {
            steps {
                withCredentials([string(credentialsId: 'Github_Integration', variable: 'GITHUB_API_TOKEN')]) {
                    script {
                        def sandboxFetch = load 'ps/jenkins/sandbox_fetch.groovy'
                        sandboxFetch.fetch(
                            'https://raw.githubusercontent.com/Percona-Lab/mysql-sandbox/main/jenkins',
                            ['check_mysql_repo_ai.py']
                        )
                    }
                }
            }
        }
        stage('Analyze') {
            steps {
                script {
                    def bedrock = params.USE_BEDROCK ? '1' : '0'
                    withEnv([
                        "CLAUDE_CODE_USE_BEDROCK=${bedrock}",
                        "CLAUDE_MODEL=${params.MODEL}",
                        "CLAUDE_EFFORT=${params.EFFORT}",
                    ]) {
                        sh '''
                            set -o errexit
                            set -o xtrace
                            mkdir -p csv_ai
                            found=0
                            for csv in *_no_ai.csv; do
                                [ -f "$csv" ] || continue
                                found=1
                                python3 check_mysql_repo_ai.py \
                                    -i mysql-server-repo \
                                    -o csv_ai \
                                    --model "$CLAUDE_MODEL" \
                                    --effort "$CLAUDE_EFFORT" \
                                    "$csv"
                            done
                            if [ "$found" -eq 0 ]; then
                                echo "ERROR: no *_no_ai.csv was copied"
                                exit 1
                            fi
                        '''
                    }
                }
            }
        }
    }
    post {
        always {
            archiveArtifacts artifacts: 'csv_ai/*.csv', allowEmptyArchive: true
            sh '''
                sudo rm -rf ./* || true
            '''
            deleteDir()
        }
    }
}
