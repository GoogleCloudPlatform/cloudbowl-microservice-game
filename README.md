Cloud Bowl
----------

A game where microservices battle each other in a giant real-time bowl.


Run Locally:
1. Make sure you have docker installed and running
1. Start Kafka
    ```
    ./sbt "runMain apps.dev.KafkaApp"
    ```
1. Start the sample service
    ```
    (cd samples/scala-play; ./sbt run)
    ```
1. Start the Battle
    ```
    ./sbt "runMain apps.Battle"
    ```
1. Start the apps.dev Kafka event viewer
    ```
    ./sbt "runMain apps.dev.KafkaConsumerApp"
    ```
1. Start the apps.dev Kafka event producer
    ```
    ./sbt "runMain apps.dev.KafkaProducerApp"
    ```
    You can send commands like
    ```
    ARENA/create
    ARENA/playerjoin
    ARENA/playerleave/ID
    ARENA/viewerjoin
    ARENA/scoresreset
    ```
1. Start the Viewer web app
    ```
    ./sbt run
    ```
    Check out the *foo* arena: [http://localhost:9000/foo](http://localhost:9000/foo)


Web UI Notes:

Pause the Arena refresh:
```
document.body.dataset.paused = true;
```


## Run on Google Cloud

1. Create GKE Cluster with Cloud Run
    ```
    gcloud config set core/project YOUR_PROJECT

    gcloud services enable compute.googleapis.com container.googleapis.comcontainer.googleapis.com containerregistry.googleapis.com cloudbuild.googleapis.com

    gcloud config set compute/region us-central1
    gcloud config set container/cluster cloudbowl

    gcloud container clusters create \
      --region=$(gcloud config get-value compute/region) \
      --addons=HorizontalPodAutoscaling,HttpLoadBalancing,CloudRun \
      --machine-type=n1-standard-4 \
      --enable-stackdriver-kubernetes \
      --enable-ip-alias \
      --enable-autoscaling --num-nodes=1 --min-nodes=0 --max-nodes=20 \
      --release-channel=stable \
      --scopes cloud-platform \
      $(gcloud config get-value container/cluster)
    ```

1. Install Strimzi Kafka Operator
    ```
    kubectl create namespace kafka
    curl -L https://github.com/strimzi/strimzi-kafka-operator/releases/download/0.20.0/strimzi-cluster-operator-0.20.0.yaml \
      | sed 's/namespace: .*/namespace: kafka/' \
      | kubectl apply -f - -n kafka
    ```
1. Setup the Kafka Cluster
    ```
    kubectl apply -n kafka -f .infra/kafka.yaml
    kubectl wait -n kafka kafka/cloudbowl --for=condition=Ready --timeout=300s
    ```
1. Get your IP Address:
    ```
    export IP_ADDRESS=$(kubectl get svc istio-ingress -n gke-system -o 'jsonpath={.status.loadBalancer.ingress[0].ip}')
    echo $IP_ADDRESS
    ```
1. Create a ConfigMap named `cloudbowl-config`:
    ```
    export APPLICATION_SECRET=$(head -c 32 /dev/urandom | base64)
    export ADMIN_PASSWORD=PICK_A_PASSWORD # Used for creating / updating arenas
    cat <<EOF | kubectl apply -f -
    apiVersion: v1
    kind: ConfigMap
    metadata:
      name: cloudbowl-config
    data:
      WEBJARS_USE_CDN: 'true'
      APPLICATION_SECRET: $APPLICATION_SECRET
      ADMIN_PASSWORD: $ADMIN_PASSWORD
    EOF
    ```
1. Setup Cloud Build with a trigger on master, excluding `samples/**`, with Configuration Type set to *Cloud Build configuration file*, and substitution vars `_CLOUDSDK_COMPUTE_REGION` and `_CLOUDSDK_CONTAINER_CLUSTER`.  Running the trigger will create the Kafka topics, deploy the battle service, and the web app.
1. Once the service is deployed, setup the domain name:
    ```
    export IP_ADDRESS=$(kubectl get svc istio-ingress -n gke-system -o 'jsonpath={.status.loadBalancer.ingress[0].ip}')
    echo "IP_ADDRESS=$IP_ADDRESS"

    gcloud beta run domain-mappings create --service cloudbowl-web --domain $IP_ADDRESS.nip.io --platform=gke --project=$(gcloud config get-value core/project) \
      --cluster=$(gcloud config get-value container/cluster) --cluster-location=$(gcloud config get-value compute/region)
    gcloud compute addresses create cloudbowl-ip --addresses=$IP_ADDRESS --region=$(gcloud config get-value compute/region)
    ```
1. Turn on TLS support:
    ```
    kubectl patch cm config-domainmapping -n knative-serving -p '{"data":{"autoTLS":"Enabled"}}'
    kubectl get kcert
    ```

### Troubleshooting


```
# Pick a topic:
export TOPIC=viewer-ping
export TOPIC=players-refresh
export TOPIC=arena-update

# Describe a topic:
kubectl -n kafka run kafka-consumer -ti --image=strimzi/kafka:0.17.0-kafka-2.4.0 --rm=true --restart=Never -- bin/kafka-topics.sh --describe --bootstrap-server cloudbowl-kafka-bootstrap.kafka:9092 --topic $TOPIC

# Consume messages on a topic:
kubectl -n kafka run kafka-consumer -ti --image=strimzi/kafka:0.17.0-kafka-2.4.0 --rm=true --restart=Never -- bin/kafka-console-consumer.sh --bootstrap-server cloudbowl-kafka-bootstrap.kafka:9092 --topic $TOPIC --from-beginning --property print.key=true --property key.separator=":"
```
