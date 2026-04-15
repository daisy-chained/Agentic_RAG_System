FROM python:3.11-slim

WORKDIR /app

# Install dependencies first for layer caching
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy only the application files — excludes venv/, __pycache__, etc.
COPY ai_service_pb2.py ai_service_pb2_grpc.py main.py ./

EXPOSE 50051

CMD ["python3", "main.py"]
