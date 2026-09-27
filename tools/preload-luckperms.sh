#!/bin/sh
# Pre-downloads the libraries that LuckPerms needs at runtime.
# Meant to be used from a Dockerfile.
# Must run from the proxy working directory (/proxy) with proxy.jar and
# plugins/LuckPerms.jar already in place.
set -eu

cd /proxy

# Make LuckPerms download the MongoDB client libraries instead of H2
export LUCKPERMS_STORAGE_METHOD="${LUCKPERMS_STORAGE_METHOD:-mongodb}"
export LUCKPERMS_DATA_MONGODB_CONNECTION_URI="${LUCKPERMS_DATA_MONGODB_CONNECTION_URI:-mongodb://localhost:27017}"
export LUCKPERMS_DATA_DATABASE="${LUCKPERMS_DATA_DATABASE:-luckperms}"

java -jar proxy.jar >/tmp/luckperms-preload.log 2>&1 &
pid="$!"

# Wait until LuckPerms has finished downloading its libraries. It always logs "Loading storage
# provider..." after all the downloads are finished.
for _ in $(seq 1 120); do
    kill -0 "$pid" 2>/dev/null || break
    if grep -qE "Loading storage provider|Unable to load dependency" /tmp/luckperms-preload.log 2>/dev/null; then
        break
    fi
    sleep 1
done

kill "$pid" 2>/dev/null || true
wait "$pid" 2>/dev/null || true

if grep -q "Unable to load dependency" /tmp/luckperms-preload.log 2>/dev/null; then
    echo "LuckPerms failed to download one or more dependencies:" >&2
    grep "Unable to load dependency" /tmp/luckperms-preload.log >&2
    exit 1
fi

# Drop everything the throwaway run generated except the dependency cache.
rm -rf logs lang velocity.toml forwarding.secret plugins/luckperms/config.yml plugins/bStats
rm -f /tmp/luckperms-preload.log

# Fail the build if nothing was preloaded. LuckPerms itself logs nothing on a successful
# download, so print what we ended up with.
test -n "$(find plugins/luckperms/libs -name '*.jar' -print -quit)"
echo "Preloaded LuckPerms libraries:"
find plugins/luckperms/libs -name '*.jar' ! -name '*-remapped.jar' | sort | sed 's#.*/#  #'
