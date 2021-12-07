Cloud Bowl Sample - Scala Node.js
---------------------------------

To make changes, edit the `web.js` file.

Run Locally (Dev):
```
npm install
npm run start
```

Deploy:

[![Run on Google Cloud](https://deploy.cloud.run/button.svg)](https://deploy.cloud.run)

Containerize & Run Locally:
```
export PROJECT_ID=YOUR_GCP_PROJECT_ID
pack build --builder=gcr.io/buildpacks/builder gcr.io/$PROJECT_ID/cloudbowl-samples-nodejs
docker run -it -ePORT=8080 -p8080:8080 gcr.io/$PROJECT_ID/cloudbowl-samples-nodejs
```

