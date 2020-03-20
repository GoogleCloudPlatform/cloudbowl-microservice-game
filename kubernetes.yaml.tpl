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
          valueFrom:
            configMapKeyRef:
              key: KAFKA_BOOTSTRAP_SERVERS
              name: cloudbowl-battle-config
        - name: KAFKA_CLUSTER_API_KEY
          valueFrom:
            configMapKeyRef:
              key: KAFKA_CLUSTER_API_KEY
              name: cloudbowl-battle-config
        - name: KAFKA_CLUSTER_API_SECRET
          valueFrom:
            configMapKeyRef:
              key: KAFKA_CLUSTER_API_SECRET
              name: cloudbowl-battle-config
        - name: SHEET_CLIENT_EMAIL
          valueFrom:
            configMapKeyRef:
              key: SHEET_CLIENT_EMAIL
              name: cloudbowl-battle-config
        - name: SHEET_ID
          valueFrom:
            configMapKeyRef:
              key: SHEET_ID
              name: cloudbowl-battle-config
        - name: SHEET_NAME
          valueFrom:
            configMapKeyRef:
              key: SHEET_NAME
              name: cloudbowl-battle-config
        - name: SHEET_PRIVATE_KEY
          valueFrom:
            configMapKeyRef:
              key: SHEET_PRIVATE_KEY
              name: cloudbowl-battle-config
        - name: SHEET_PRIVATE_KEY_ID
          valueFrom:
            configMapKeyRef:
              key: SHEET_PRIVATE_KEY_ID
              name: cloudbowl-battle-config
