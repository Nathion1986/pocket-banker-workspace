FROM python:3.12-slim@sha256:f77ac9e44ae96ef2c90b8053ea08c31f8be030f824196b0ae4db6d462c84e51f
ARG PROVIDER_DIR=bank-a
WORKDIR /app
COPY common ./common
COPY ${PROVIDER_DIR}/main.py ./provider/main.py
RUN useradd --system --uid 10002 banker && mkdir /app/local-data && chown -R banker:banker /app
USER banker
ENV MOCK_BIND_ADDRESS=0.0.0.0 MOCK_DB_PATH=/app/local-data/provider.sqlite3
ENTRYPOINT ["python","provider/main.py"]
