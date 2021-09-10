CloudBowl - Sample - .NET
-------------------------

To make changes, edit the `Controllers/CloudBowlControllers.cs` file.

Run Locally:
```
TODO
```

[![Run on Google Cloud](https://deploy.cloud.run/button.svg)](https://deploy.cloud.run)

Containerize & Run Locally:
```
export PROJECT_ID=YOUR_GCP_PROJECT_ID
pack build --builder=gcr.io/buildpacks/builder gcr.io/$PROJECT_ID/cloudbowl-samples-dotnet
docker run -it -ePORT=8080 -p8080:8080 gcr.io/$PROJECT_ID/cloudbowl-samples-dotnet
```

Deploy on Cloud Run:
```
docker push gcr.io/$PROJECT_ID/cloudbowl-samples-dotnet

gcloud run deploy cloudbowl-samples-dotnet \
          --project=$PROJECT_ID \
          --platform=managed \
          --region=us-central1 \
          --image=gcr.io/$PROJECT_ID/cloudbowl-samples-dotnet \
          --allow-unauthenticated
```
