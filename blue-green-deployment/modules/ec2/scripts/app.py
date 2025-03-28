from flask import Flask

app = Flask(__name__)

@app.route('/')
def home():
    return "Hello, Blue-Green Deployment on Amazon Linux 2! Tanishq here New Version V2 v3 V4!V5! V6 v7 V8 V9 V10 v11 v12 v13 v14 v15 v16 V17"

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5000)
