Cloud Bowl Sample - Python
---------------------------------

To make changes, edit the `main.py` file.

It's recommended to run everything in a virtual environment. You can find a
guide how to set this up [here](https://docs.python.org/3/library/venv.html)

Run Locally (Dev):

```python
pip install -r requirements.txt
python3 main.py
```

Deploy to Cloud Run:

[![Run on Google Cloud](https://deploy.cloud.run/button.svg)](https://deploy.cloud.run)

Containerize & Run Locally:
```
export PROJECT_ID=YOUR_GCP_PROJECT_ID
pack build --builder=gcr.io/buildpacks/builder gcr.io/$PROJECT_ID/cloudbowl-samples-python
docker run -it -ePORT=8080 -p8080:8080 gcr.io/$PROJECT_ID/cloudbowl-samples-python
```

