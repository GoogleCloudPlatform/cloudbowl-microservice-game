Cloud Bowl
----------

A game where microservices battle each other in a giant real-time bowl.


Run Locally:
1. Make sure you have docker installed and running
1. Start Kafka
    ```
    ./sbt "runMain apps.dev.KafkaApp"
    ```
1. Start the Battle

    TODO: player backends
    ```
    ./sbt "runMain apps.Battle"
    ```
1. Start the apps.dev Kafka event viewer
    ```
    ./sbt "runMain apps.dev.KafkaConsumerApp"
    ```
1. Start the sample service
    ```
    cd samples/scala-play
    ./sbt run
    ```
1. Start the apps.dev Kafka event producer
    ```
    ./sbt "runMain apps.dev.KafkaProducerApp"
    ```
    You can send commands like
    ```
    ARENA/viewerjoin
    ARENA/playersrefresh
    ```
1. Start the Viewer web app
    ```
    ./sbt run
    ```
    Check out the *foo* arena: [http://localhost:9000/foo](http://localhost:9000/foo)


Testing:

For GitHub Player backend:

1. Create a GitHub App with perm *Contents - Read-Only*
1. Generate a private key
1. `export GITHUB_APP_PRIVATE_KEY=$(cat ~/somewhere/your-integration.2017-02-07.private-key.pem)`
1. `export GITHUB_APP_ID=YOUR_NUMERIC_GITHUB_APP_ID`
1. `export GITHUB_ORGREPO=cloudbowl/arenas`
1. Run the tests:
    ```
    ./sbt test
    ```

For Google Sheets Player backend:

1. TODO


Prod:
1. Create GKE Cluster with Cloud Run
    ```
    gcloud config set compute/region us-central1
    gcloud config set container/cluster cloudbowl
    gcloud container clusters create \
      --region=$(gcloud config get-value compute/region) \
      --addons=HorizontalPodAutoscaling,HttpLoadBalancing,CloudRun \
      --machine-type=n1-standard-4 \
      --enable-stackdriver-kubernetes \
      --enable-ip-alias \
      --enable-autoscaling --num-nodes=3 --min-nodes=0 --max-nodes=10 \
      --enable-autorepair \
      --scopes cloud-platform \
      $(gcloud config get-value container/cluster)
    ```
1. Install Strimzi Kafka Operator
    ```
    kubectl create namespace kafka
    curl -L https://github.com/strimzi/strimzi-kafka-operator/releases/download/0.17.0/strimzi-cluster-operator-0.17.0.yaml \
      | sed 's/namespace: .*/namespace: kafka/' \
      | kubectl apply -f - -n kafka
    ```
1. Setup the Kafka Cluster
    ```
    kubectl apply -n kafka -f .infra/kafka.yaml
    kubectl wait -n kafka kafka/cloudbowl --for=condition=Ready --timeout=300s
    ```
1. Setup the domain name (for example with nip.io)
    ```
    export IP_ADDRESS=$(kubectl get svc istio-ingress -n gke-system \
      -o 'jsonpath={.status.loadBalancer.ingress[0].ip}')
    
    cat <<EOF | kubectl apply -f -
    apiVersion: v1
    kind: ConfigMap
    metadata:
      name: config-domain
      namespace: knative-serving
    data:
      $IP_ADDRESS.nip.io: ""
    EOF
   
    echo "$IP_ADDRESS.nip.io"
    ```
1. Turn on TLS support:
    ```
    kubectl patch cm config-domainmapping -n knative-serving -p '{"data":{"autoTLS":"Enabled"}}'
    ```
1. Create a GitHub App, with a push event WebHook to your web app (i.e. https://cloudbowl-web.default.35.184.62.161.nip.io/playersrefresh) and with a preshared key you have made up.  For premissions, select Contents *Read-only* and for Events select *Push*.
1. Generate a Private Key for the GitHub App
1. Install the GitHub App on the repo that will hold the players
1. Create a ConfigMap named `cloudbowl-battle-config`:
    ```
    cat <<EOF | kubectl apply -f -
    apiVersion: v1
    kind: ConfigMap
    metadata:
      name: cloudbowl-battle-config
    data:
      GITHUB_ORGREPO: # Your GitHub Org/Repo
      GITHUB_APP_ID: # Your GitHub App ID
      GITHUB_APP_PRIVATE_KEY: # Your GitHub App Private Key (cat YOUR.private-key.pem | sed -e ':a' -e 'N' -e '$!ba' -e 's/\n/\\n/g')
      GITHUB_PSK: # Your GitHub WebHook's preshared key
      WEBJARS_USE_CDN: 'true'
      APPLICATION_SECRET: # Generated secret key (i.e. `head -c 32 /dev/urandom | base64`)
    EOF
1. Setup Cloud Build with a trigger on master, excluding `samples/**`, and with substitution vars `_CLOUDSDK_COMPUTE_ZONE` and `_CLOUDSDK_CONTAINER_CLUSTER`.  Running the trigger will create the Kafka topics, deploy the battle service, and the web app.

# TODO

- Battle hot looping
- Persist arenas
- Fan-out battle
- Response times in player list
- Local dev instructions
