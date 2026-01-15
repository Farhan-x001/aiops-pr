// Jenkins declarative pipeline implementing CI/CD and AI-assisted self-healing.
// Design decisions (why):
// - Keep stage logic small and readable; helper functions handle complex steps.
// - Use environment variables for all secrets/tokens to avoid checking them in.
// - Validate and restrict patches to allowed paths before applying to prevent scope creep.

pipeline {
    agent any

    environment {
        // GitHub repo in form 'owner/repo'
        REPO_FULL_NAME = credentials('GITHUB_REPO_NAME')
        GITHUB_TOKEN = credentials('-')
        GEMINI_API_KEY = credentials('-')
    GEMINI_API_URL = 'https://generativelanguage.googleapis.com/v1beta/models/gemini-3-flash-preview:generateContent'
        DOCKER_REGISTRY = credentials('DOCKER_REGISTRY')
        DOCKER_CREDENTIALS_ID = 'docker-creds'
        IMAGE_NAME = "${DOCKER_REGISTRY}/aiops-pr"
        KUBECONFIG_CREDENTIALS_ID = 'kubeconfig-file'
        // Branch prefix for AI fixes
        AI_BRANCH_PREFIX = 'ai-fix'
    }

    options {
        // Keep build logs reasonably sized
        timestamps()
        timeout(time: 60, unit: 'MINUTES')
    }

    stages {
        stage('Checkout') {
            steps {
                cleanWs()
                checkout scm
            }
        }

        stage('Maven Build') {
            steps {
                script {
                    try {
                        // Capture full mvn output to mvn.log so failure context can be sent to the model
                        sh "mvn -DskipTests package 2>&1 | tee mvn.log"
                        archiveArtifacts artifacts: 'target/*.jar, mvn.log', fingerprint: true
                    } catch (err) {
                        // ensure mvn.log is present for diagnosis
                        sh "echo 'Maven failed: ' > mvn.log || true"
                        handleFailure('Maven Build', err.toString())
                        error("Maven build failed: ${err}")
                    }
                }
            }
        }

        stage('Parse Build Logs') {
            when { expression { fileExists('mvn.log') } }
            steps {
                script {
                    // Use Log Parser plugin to structure the mvn.log output for humans and automation
                    // parsingRulesPath should point to a rules file in the repo (jenkins/log-parser-rules.txt)
                    step([$class: 'LogParserPublisher', parsingRulesPath: 'jenkins/log-parser-rules.txt', unstableOnWarning: true, failBuildOnError: false, useProjectRule: true])
                }
            }
        }

        stage('Docker Build & Push') {
            steps {
                script {
                    def tag = "${env.BUILD_ID}"
                    def fullImage = "${IMAGE_NAME}:${tag}"
                    try {
                        sh "docker build -t ${fullImage} ."
                        withCredentials([usernamePassword(credentialsId: env.DOCKER_CREDENTIALS_ID, usernameVariable: 'DOCKER_USER', passwordVariable: 'DOCKER_PASS')]) {
                            sh "echo $DOCKER_PASS | docker login ${env.DOCKER_REGISTRY} -u $DOCKER_USER --password-stdin"
                            sh "docker push ${fullImage}"
                        }
                        // expose image reference for later stages
                        env.DEPLOY_IMAGE = fullImage
                    } catch (err) {
                        handleFailure('Docker Build', err.toString())
                        error("Docker build/push failed: ${err}")
                    }
                }
            }
        }

        stage('Kubernetes Deployment (IKP)') {
            steps {
                script {
                    try {
                        // use kubeconfig from credentials
                        withCredentials([file(credentialsId: env.KUBECONFIG_CREDENTIALS_ID, variable: 'KUBECONFIG_FILE')]) {
                            sh 'mkdir -p $HOME/.kube'
                            sh 'cp $KUBECONFIG_FILE $HOME/.kube/config'
                            // patch the deployment image to the new image
                            sh "kubectl set image deployment/aiops-pr-deployment aiops-pr=${env.DEPLOY_IMAGE} --record"
                        }
                    } catch (err) {
                        handleFailure('Kubernetes Deploy', err.toString())
                        error("Kubernetes deployment failed: ${err}")
                    }
                }
            }
        }

        stage('Rollout Verification') {
            steps {
                script {
                    try {
                        withCredentials([file(credentialsId: env.KUBECONFIG_CREDENTIALS_ID, variable: 'KUBECONFIG_FILE')]) {
                            sh 'export KUBECONFIG=$KUBECONFIG_FILE; kubectl rollout status deployment/aiops-pr-deployment --timeout=60s'
                        }
                    } catch (err) {
                        handleFailure('Rollout Verification', err.toString())
                        error("Rollout verification failed: ${err}")
                    }
                }
            }
        }

        stage('Post-deployment Health Check') {
            steps {
                script {
                    try {
                        withCredentials([file(credentialsId: env.KUBECONFIG_CREDENTIALS_ID, variable: 'KUBECONFIG_FILE')]) {
                            // Port-forward to a pod and check /health
                            sh '''
                                export KUBECONFIG=$KUBECONFIG_FILE
                                POD=$(kubectl get pods -l app=aiops-pr -o jsonpath='{.items[0].metadata.name}')
                                kubectl port-forward $POD 8081:8080 &
                                PF_PID=$!
                                sleep 2
                                curl -f http://127.0.0.1:8081/health
                                kill $PF_PID || true
                            '''
                        }
                    } catch (err) {
                        handleFailure('Health Check', err.toString())
                        error("Health check failed: ${err}")
                    }
                }
            }
        }
    }

    post {
        failure {
            script {
                // fallback: ensure we call handler in case a failure bubbled up
                handleFailure('Pipeline', 'Pipeline failed in post/failure')
            }
        }
    }
}

// ------------------- Helper functions -------------------
def handleFailure(String failingStage, String failureContext) {
    // Keep the function focused: collect logs, call Gemini, validate patch, create branch & PR

    // 1) Collect concise logs useful for diagnosis
    sh 'mkdir -p aiops-debug'
    sh 'echo "Stage: ${failingStage}" > aiops-debug/context.txt'
    sh "echo 'Failure: ${failureContext}' >> aiops-debug/context.txt"
    // collect last 500 lines of jenkins log, repo state, and include mvn.log tail if present
    sh '''
        if [ -f /var/log/jenkins/jenkins.log ]; then tail -n 500 /var/log/jenkins/jenkins.log > aiops-debug/jenkins.log; fi
        git --no-pager log -n 10 --pretty=format:'%h %s' > aiops-debug/recent-commits.txt
        git status --porcelain > aiops-debug/git-status.txt || true
        if [ -f mvn.log ]; then echo '\n--- MVN LOG (tail 1000 lines) ---' >> aiops-debug/context.txt; tail -n 1000 mvn.log >> aiops-debug/context.txt; fi
    '''

    // 2) Prepare strict Gemini prompt
    def geminiPrompt = '''
You are an automated patch-generator. INPUT: a short failure context plus repository files.

Rules (must be enforced):
- Output ONLY a unified git diff patch (git format: diff --git a/... b/...), nothing else.
- No markdown, no commentary, no explanations, no extra text.
- If you are unsure or cannot produce a safe patch, return an empty response.
- PATCH MUST only modify files under these allowed paths: src/**, pom.xml, Dockerfile, k8s/**

Task: Propose a minimal, compile-safe patch that would likely fix the problem described below.

Failure context:
${readFile('aiops-debug/context.txt')}

Repository note: Only modify files listed above. Keep changes small and focused. Use existing project style.

Always output a valid unified git diff or an empty string.
'''

    writeFile file: 'aiops-debug/gemini-prompt.txt', text: geminiPrompt

    // 3) Build a small repository context (only allowed files) and call Gemini with multipart
    sh '''
        set -e
        mkdir -p aiops-debug
        REPO_CTX=aiops-debug/repo-context.tar.gz
        # collect only allowed files tracked by git to keep context small
        FILES=$(git ls-files 'src/**' 'k8s/**' 'Dockerfile' 'pom.xml' 2>/dev/null || true)
        if [ -n "$FILES" ]; then
            tar -czf ${REPO_CTX} $FILES || true
        else
            # empty archive
            tar -czf ${REPO_CTX} --files-from=/dev/null
        fi

        RESPONSE_JSON=aiops-debug/response.json
        RESPONSE_FILE=aiops-debug/patch.diff
        # Encode repo context as base64 and embed it in the prompt so we can call the Generative Language API
        if command -v base64 >/dev/null 2>&1; then
            base64 -w0 ${REPO_CTX} > aiops-debug/repo-context.b64 2>/dev/null || base64 ${REPO_CTX} > aiops-debug/repo-context.b64
        else
            # fallback: create an empty marker if base64 missing
            echo '' > aiops-debug/repo-context.b64
        fi

        # Build a JSON payload using Python to safely escape content
        python - <<PY > aiops-debug/payload.json
import json,sys
prompt = open('aiops-debug/gemini-prompt.txt','r', encoding='utf-8').read()
repo_b64 = open('aiops-debug/repo-context.b64','r', encoding='utf-8').read()
if repo_b64:
    prompt = prompt + '\n\n---REPO-CONTEXT-BASE64-BEGIN---\n' + repo_b64 + '\n---REPO-CONTEXT-BASE64-END---\n'
payload = {"contents": [{"parts": [{"text": prompt}]}]}
json.dump(payload, sys.stdout)
PY

        # Call the Generative Language API using the x-goog-api-key header (per sample)
        curl -s -X POST \
          -H "x-goog-api-key: ${GEMINI_API_KEY}" \
          -H "Content-Type: application/json" \
          --data-binary @aiops-debug/payload.json \
          "${GEMINI_API_URL}" > ${RESPONSE_JSON} || true

        # Extract likely textual output from known response shapes into a plain file (patch.diff)
        python - <<PY > ${RESPONSE_FILE}
import json
import sys
try:
    r = json.load(open('aiops-debug/response.json','r',encoding='utf-8'))
except Exception:
    sys.exit(0)
out = ''
if isinstance(r, dict):
    # try common patterns
    if 'candidates' in r:
        for c in r.get('candidates',[]):
            content = c.get('content')
            if isinstance(content, list):
                for item in content:
                    if isinstance(item, dict):
                        if 'text' in item:
                            out += item['text']
                        if 'parts' in item:
                            for p in item.get('parts',[]):
                                if isinstance(p, dict) and 'text' in p:
                                    out += p['text']
            elif isinstance(content, dict):
                out += content.get('text','')
    # fallback shapes
    if not out and 'output' in r:
        out = r.get('output','')
    if not out and 'response' in r:
        out = str(r.get('response',''))
# write whatever we collected (may be empty)
open('aiops-debug/patch.diff','w', encoding='utf-8').write(out)
PY
    '''

    // 4) Validate response existence
    def patchContent = readFile('aiops-debug/patch.diff').trim()
    if (!patchContent) {
        echo 'Gemini returned empty output — aborting automated patch.'
        return
    }

    // 5) Quick safety checks: ensure unified diff format and allowed files only
    writeFile file: 'aiops-debug/patch.diff', text: patchContent

    // extract changed file paths
    sh "grep -E '^\\+\\+\\+ b/' aiops-debug/patch.diff | sed -E 's/^\+\+\+ b\///' > aiops-debug/changed-files.txt || true"
    def changed = readFile('aiops-debug/changed-files.txt').readLines().findAll { it }
    if (!changed) {
        echo 'No changed files found in patch; aborting.'
        return
    }

    // allowed patterns
    def allowed = ['src/', 'pom.xml', 'Dockerfile', 'k8s/']
    def disallowed = changed.findAll { path ->
        !allowed.any { prefix -> path.startsWith(prefix) }
    }
    if (disallowed) {
        echo "Patch touches disallowed paths: ${disallowed}; aborting to preserve safety."
        return
    }

    // 6) Apply patch on an appropriate branch, commit, push, and create PR
    // Design: If the pipeline ran on a feature branch, apply the AI patch to that same branch
    // so the developer gets the fix in their branch. If running on `main` or no branch, create
    // a new ai-fix branch to avoid modifying main directly.
    def currentBranch = sh(script: "git rev-parse --abbrev-ref HEAD", returnStdout: true).trim()
    def branchName = currentBranch
    if (!branchName || branchName == 'HEAD' || branchName == 'main') {
        branchName = "${env.AI_BRANCH_PREFIX}-${env.BUILD_ID}"
        sh "git checkout -b ${branchName}"
    } else {
        // ensure local branch is checked out
        sh "git checkout ${branchName}"
    }

    // validate patch applies cleanly
    def applyCheck = sh(script: "git apply --check aiops-debug/patch.diff", returnStatus: true)
    if (applyCheck != 0) {
        echo 'Patch failed git apply --check; aborting.'
        return
    }

    sh 'git apply aiops-debug/patch.diff'
    sh "git add -A && git commit -m 'chore(ai): AI-assisted patch for ${failingStage} (build ${env.BUILD_ID})' || echo 'No changes to commit'"
    // push to same branch; CI user must have push rights to this branch
    sh "git push origin ${branchName}"

    // Create PR via GitHub API. Add a small MCP header to indicate automated model-context PRs
    def prTitle = "AI-assisted fix: ${failingStage} (${env.BUILD_ID})"
    def prBody = "This PR contains an AI-suggested patch for failing stage: ${failingStage}. Human review required."
    sh '''
        curl -s -X POST -H "Authorization: token ${GITHUB_TOKEN}" -H "Accept: application/vnd.github.v3+json" -H "X-GitHub-MCP: 1" \
          https://api.github.com/repos/${REPO_FULL_NAME}/pulls \
          -d "{\"title\": \"${prTitle}\", \"head\": \"${branchName}\", \"base\": \"main\", \"body\": \"${prBody}\"}"
    '''

    echo "AI-assisted patch applied to branch ${branchName} and PR created. Human review required — no auto-merge performed."
}
