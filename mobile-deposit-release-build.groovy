// This script reads the manifest from an upstream job
// it compares with the last successful manifest from this build
// based on the differences, it triggers deploys in parallel
// On completion of the deploys it runs various tests
// Note multiple triggers with the same parameters are coalesced

sleepDuration=1
def productName='mobile-deposit'
def manifestLocation="$productName-update-release-manifest"
def manifestFileName='manifest'
branch="/qcon"

// Application configuration data
// Long term this would be sourced from external environment configuration
// Deployment scripts would be supplied as part of the application builds artifacts
host="localhost"
hostConfigPorts=[:]
hostConfigShutdownpaths=[:]
hostConfigPorts["${productName}-api"]="9010"
hostConfigPorts["${productName}-ui"]="9011"
hostConfigShutdownpaths["${productName}-api"]= "spring/shutdown"
hostConfigShutdownpaths["${productName}-ui"]= "shutdown"

pids = []

node ("linux") {

    stage name: 'Reading Manifest'
    step([$class: 'CopyArtifact', filter: manifestFileName, projectName: manifestLocation, selector: [$class: 'StatusBuildSelector', stable: false]])
    sh "mv manifest targetmanifest"
    requiredVersions = readPropertiesFromFile("targetmanifest")

    try {
        step([$class: 'CopyArtifact', filter: manifestFileName, projectName:env.JOB_NAME, selector: [$class: 'StatusBuildSelector', stable: false]])
        sh "mv manifest currentmanifest"
        currentVersions = readPropertiesFromFile("currentmanifest")
    } catch (Exception e) {
        echo e.toString()
        currentVersions = new Properties()
    }

    stage name: 'Determining Updated Apps'

    updatedVersions = compareVersions( requiredVersions, currentVersions)
    //appsToUpdate=updatedVersions.stringPropertyNames().toArray()
    
    appsToUpdate=requiredVersions.stringPropertyNames().toArray()
    
    stage name: 'Updating Apps'
    checkpoint 'Starting App Update'

    if (appsToUpdate.size()>0) {
        log "Update Apps", "The following apps require updating: ${appsToUpdate.toString()}"

        def branches = [:]
        for (i=0; i < appsToUpdate.size(); i++) {
            def app=appsToUpdate[i]
            def revision = requiredVersions.getProperty(app)
            branches[app] = {
                decom(app, revision)
                deploy (app, revision)
            }
        }
        parallel branches
    }
    writePropertiesFile(requiredVersions, "manifest")
    archive 'manifest'
    writePropertiesFile(updatedVersions, "updates")
    archive 'updates'

}

stage concurrency: 1, name: 'Perform NFT'
checkpoint 'Starting NFT'
performNFT()

input 'Kill running servers?'

node("linux") {
    for (i=0; i < pids.size(); i++) {
        def pid=pids[i]
        sh "kill $pid"
    }
}


// ##################################################################################
//
//   Functions
//
// ##################################################################################


def compareVersions ( requiredVersions, currentVersions) {

    currentapps = currentVersions.stringPropertyNames().toArray()
    reqapps = requiredVersions.stringPropertyNames().toArray()
    Properties updatedVersions = new Properties()

    for (i=0; i < reqapps.size(); i++) {

        def app=reqapps[i]

        if (currentVersions.getProperty(app) == requiredVersions.getProperty(app) ) {
            log "Calculating Deltas", "Correct version of $app already deployed"
        } else {
            log "Calculating Deltas", "Adding $app for deployment"
            updatedVersions.setProperty(app, requiredVersions.getProperty(app))
        }
    }

    return updatedVersions
}


def decom(longapp, revision) {
    def app=longapp.minus(branch)
    def port=hostConfigPorts[app]
    def shutdownpath=hostConfigShutdownpaths[app]
    def cmd="http://${host}:${port}/${shutdownpath}"
    log("decom", "app=${app}; port=${port}; shutdownpath=${shutdownpath}")
    node ("linux") {
        try {
            sh "curl -X POST $cmd"
        } catch (Exception e) {
            log("decom", e.toString())
        }
        sleep time: sleepDuration
    }
}

def deploy(longapp, revision) {
    def app=longapp.minus(branch)
    def port=hostConfigPorts[app]
    def apiport=hostConfigPorts["mobile-deposit-api"]

    node ("linux") {
        log ("Deploy", """Perform the deploy steps here for app: $app:$revision
eg call sh /scripts/$app/deploy nft $revision""")
        def splitstr = revision.split( "-")
        build = splitstr[splitstr.length-1]
        echo "build = $build"
        dir('artifacts') {
            step([$class: 'CopyArtifact', projectName: "${longapp}", selector: [$class: 'SpecificBuildSelector', buildNumber: build]])
            sh "ls -l"
            sh "java -jar target/${app}-${revision}.jar --server.port=$port --api.port=${apiport} 2>/dev/null 1>myfile.log & echo \$! > pid"
            def s = readFile 'pid'
            String pid = s.trim()
            pids.add(pid)

        }


        sleep time: sleepDuration
    }
}

def performNFT() {
    node ("nft-runner") {
        log ("Run NFT",  "Perform the NFT steps")
        sleep time: sleepDuration
    }
}



def getBlockedBuilds(fullName) {
    def q = Jenkins.instance.queue
    items = q.items
    Items[] matches = []
    for (hudson.model.Queue.Item item : items) {
        if (item.task.fullName==env.JOB_NAME) {
            log "Matched item", "matched item $item"
            matches.add( item)
        }
    }
    return matches
}

def writePropertiesFile(props, file) {
    log "WriteProperties", "File = $file"
    writeFile file: file, text: writeProperties(props)
}

@NonCPS def writeProperties (props) {
    def sw = new StringWriter()
    props.store(sw, null)
    return sw.toString()
}

def readPropertiesFromFile (file) {
    log "ReadProperties", "File = $file"
    def str = readFile file: file, charset : 'utf-8'
    def sr = new StringReader(str)
    def props = new Properties()
    props.load(sr)
    return props
}

def log (step, msg) {

    echo """************************************************************
Step: $step
$msg
************************************************************"""
}
