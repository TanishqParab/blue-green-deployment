from flask import Flask

app = Flask(__name__)

@app.route('/')
def home():
    return "Hello, Blue-Green Deployment on Amazon Linux 2! Tanishq here New Version V2! V3! V4! V5! V6 V7! V8! V9 V10 V11 V12 v13 V14 V15 V16 V17 V18 v19! v20 v21"

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5000)
