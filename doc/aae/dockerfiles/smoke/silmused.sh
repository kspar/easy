#!/bin/sh
# Is this silmused image fit to grade with?
#
# silmused grades SQL, so the interesting half of this image is not the Python package — it is
# whether the postgres it carries actually starts. An image where pip succeeded and the cluster is
# broken would fail on the first real submission, which is exactly what this is here to prevent.
#
# Run with `--network none`, so postgres is reached over the local socket and nothing leaves.
set -eu

python3 /easy-smoke-expect-versions.py

python3 -c "import silmused; print('silmused imports')"

# Port 5433, not 5432: the Dockerfile moves it, and a check that assumed the default would pass
# against a cluster nothing else can reach.
pg_ctlcluster 18 main start
trap 'pg_ctlcluster 18 main stop || true' EXIT

for attempt in 1 2 3 4 5 6 7 8 9 10; do
    if pg_isready -p 5433 -q; then break; fi
    sleep 1
done
pg_isready -p 5433

# Prove the credentials the grader uses actually work, not merely that a socket is open.
PGPASSWORD=postgres psql -h 127.0.0.1 -p 5433 -U postgres -Atqc 'select 1' | grep -qx 1

echo "smoke: silmused ok"
