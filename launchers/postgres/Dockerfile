# -buster is required to have apt available
FROM postgres:18.0

RUN apt update && apt install -y postgresql-17-wal2json postgresql-contrib

HEALTHCHECK --interval=5s --timeout=5s --retries=10 CMD curl --fail http://localhost:8080/api/check/health

CMD ["postgres", "-c", "wal_level=logical"]
