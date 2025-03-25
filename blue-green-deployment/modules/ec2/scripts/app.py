from flask import Flask

app = Flask(__name__)

@app.route('/')
def home():
    return "Hello, Blue-Green Deployment on Amazon Linux 2! Tanishq here New Version V2! V3! V4! V5! V6 V7! V8!"

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5000)
