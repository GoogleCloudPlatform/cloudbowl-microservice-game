apiVersion: kafka.strimzi.io/v1beta1
kind: KafkaTopic
metadata:
  name: players-refresh
  labels:
    strimzi.io/cluster: cloudbowl
spec:
  partitions: 10
  replicas: 2
---
apiVersion: kafka.strimzi.io/v1beta1
kind: KafkaTopic
metadata:
  name: viewer-ping
  labels:
    strimzi.io/cluster: cloudbowl
spec:
  partitions: 10
  replicas: 2
---
apiVersion: kafka.strimzi.io/v1beta1
kind: KafkaTopic
metadata:
  name: arena-update
  labels:
    strimzi.io/cluster: cloudbowl
spec:
  partitions: 10
  replicas: 2
---
apiVersion: apps/v1
kind: Deployment
metadata:
  labels:
    app: cloudbowl-battle
  name: cloudbowl-battle
spec:
  replicas: 1
  selector:
    matchLabels:
      app: cloudbowl-battle
  template:
    metadata:
      labels:
        app: cloudbowl-battle
    spec:
      containers:
      - name: $REPO_NAME
        image: gcr.io/$PROJECT_ID/$REPO_NAME:$COMMIT_SHA
        imagePullPolicy: IfNotPresent
        args:
        - battle
        env:
        - name: KAFKA_BOOTSTRAP_SERVERS
          value: cloudbowl-kafka-bootstrap.kafka:9092
        - name: GITHUB_ORGREPO
          valueFrom:
            configMapKeyRef:
              key: GITHUB_ORGREPO
              name: cloudbowl-battle-config
        - name: GITHUB_APP_ID
          valueFrom:
            configMapKeyRef:
              key: GITHUB_APP_ID
              name: cloudbowl-battle-config
        - name: GITHUB_APP_PRIVATE_KEY
          valueFrom:
            configMapKeyRef:
              key: GITHUB_APP_PRIVATE_KEY
              name: cloudbowl-battle-config
#        - name: KAFKA_CLUSTER_API_KEY
#          valueFrom:
#            configMapKeyRef:
#              key: KAFKA_CLUSTER_API_KEY
#              name: cloudbowl-battle-config
#        - name: KAFKA_CLUSTER_API_SECRET
#          valueFrom:
#            configMapKeyRef:
#              key: KAFKA_CLUSTER_API_SECRET
#              name: cloudbowl-battle-config
#        - name: SHEET_CLIENT_EMAIL
#          valueFrom:
#            configMapKeyRef:
#              key: SHEET_CLIENT_EMAIL
#              name: cloudbowl-battle-config
#        - name: SHEET_ID
#          valueFrom:
#            configMapKeyRef:
#              key: SHEET_ID
#              name: cloudbowl-battle-config
#        - name: SHEET_NAME
#          valueFrom:
#            configMapKeyRef:
#              key: SHEET_NAME
#              name: cloudbowl-battle-config
#        - name: SHEET_PRIVATE_KEY
#          valueFrom:
#            configMapKeyRef:
#              key: SHEET_PRIVATE_KEY
#              name: cloudbowl-battle-config
#        - name: SHEET_PRIVATE_KEY_ID
#          valueFrom:
#            configMapKeyRef:
#              key: SHEET_PRIVATE_KEY_ID
#              name: cloudbowl-battle-config
---
apiVersion: serving.knative.dev/v1alpha1
kind: Service
metadata:
  name: cloudbowl-web
spec:
  template:
    spec:
      containers:
        - image: gcr.io/$PROJECT_ID/$REPO_NAME:$COMMIT_SHA
          args:
            - web
          env:
            - name: KAFKA_BOOTSTRAP_SERVERS
              value: cloudbowl-kafka-bootstrap.kafka:9092
            - name: GITHUB_PSK
              valueFrom:
                configMapKeyRef:
                  key: GITHUB_PSK
                  name: cloudbowl-battle-config
            - name: WEBJARS_USE_CDN
              valueFrom:
                configMapKeyRef:
                  key: WEBJARS_USE_CDN
                  name: cloudbowl-battle-config
            - name: APPLICATION_SECRET
              valueFrom:
                configMapKeyRef:
                  key: APPLICATION_SECRET
                  name: cloudbowl-battle-config
    metadata:
      annotations:
        autoscaling.knative.dev/class: hpa.autoscaling.knative.dev
        autoscaling.knative.dev/metric: cpu
        autoscaling.knative.dev/target: "75"
        autoscaling.knative.dev/minScale: "2"
        autoscaling.knative.dev/maxScale: "10"
