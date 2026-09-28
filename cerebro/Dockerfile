FROM python:3.11-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
ENV ANONYMIZED_TELEMETRY=False
COPY app.py .
RUN useradd -m cerebro && mkdir -p /datos && chown cerebro /datos
USER cerebro
EXPOSE 8000
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000"]
