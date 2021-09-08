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
pack build --builder=gcr.io/buildpacks/builder cloudbowl-samples-dotnet
docker run -it -ePORT=8080 -p8080:8080 cloudbowl-samples-dotnet
```