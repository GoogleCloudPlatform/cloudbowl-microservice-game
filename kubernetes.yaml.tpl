apiVersion: apps/v1
kind: Deployment
metadata:
  labels:
    app: cloudbowl
  name: cloudbowl
  namespace: default
spec:
  replicas: 1
  selector:
    matchLabels:
      app: cloudbowl
  template:
    metadata:
      labels:
        app: cloudbowl
    spec:
      containers:
      - name: $REPO_NAME
        image: gcr.io/$PROJECT_ID/$REPO_NAME:$COMMIT_SHA
        imagePullPolicy: IfNotPresent
        env:
        - name: KAFKA_BOOTSTRAP_SERVERS
          valueFrom:
            configMapKeyRef:
              key: KAFKA_BOOTSTRAP_SERVERS
              name: cloudbowl-config-scgr
        - name: KAFKA_CLUSTER_API_KEY
          valueFrom:
            configMapKeyRef:
              key: KAFKA_CLUSTER_API_KEY
              name: cloudbowl-config-scgr
        - name: KAFKA_CLUSTER_API_SECRET
          valueFrom:
            configMapKeyRef:
              key: KAFKA_CLUSTER_API_SECRET
              name: cloudbowl-config-scgr
        - name: APPLICATION_SECRET
          valueFrom:
            configMapKeyRef:
              key: APPLICATION_SECRET
              name: cloudbowl-config-scgr
        - name: WEBJARS_USE_CDN
          valueFrom:
            configMapKeyRef:
              key: WEBJARS_USE_CDN
              name: cloudbowl-config-scgr
        - name: SHEET_PSK
          valueFrom:
            configMapKeyRef:
              key: SHEET_PSK
              name: cloudbowl-config-scgr
