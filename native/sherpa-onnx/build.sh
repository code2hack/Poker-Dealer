#!/bin/sh
set -eu

module_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo_dir=$(CDPATH= cd -- "${module_dir}/../.." && pwd)
. "${module_dir}/versions.env"
grep -Fq "CPU ONNX Runtime \`${ONNX_RUNTIME_VERSION}\`" "${module_dir}/NOTICE.md"

toolchain_dir="${repo_dir}/.toolchains/sherpa-onnx/${SHERPA_ONNX_VERSION}"
archive="${toolchain_dir}/sherpa-onnx-${SHERPA_ONNX_VERSION}.aar"
mkdir -p "${toolchain_dir}" "${module_dir}/build"

if ! printf '%s  %s\n' "${SHERPA_ONNX_AAR_SHA256}" "${archive}" |
    sha256sum -c - >/dev/null 2>&1; then
    partial_archive="${archive}.part"
    curl -fL --http1.1 --retry 5 --retry-all-errors --connect-timeout 20 \
        -C - "${SHERPA_ONNX_AAR_URL}" -o "${partial_archive}"
    printf '%s  %s\n' "${SHERPA_ONNX_AAR_SHA256}" "${partial_archive}" | sha256sum -c -
    mv "${partial_archive}" "${archive}"
fi

printf '%s  %s\n' "${SHERPA_ONNX_AAR_SHA256}" "${archive}" | sha256sum -c -
jar tf "${archive}" >/dev/null

for library in libonnxruntime.so libsherpa-onnx-c-api.so libsherpa-onnx-cxx-api.so libsherpa-onnx-jni.so; do
    jar tf "${archive}" | grep -qx "jni/arm64-v8a/${library}"
done

if jar tf "${archive}" | grep -Eq '(^|/)([^/]+\.(onnx|ort)|tokens\.txt)$'; then
    printf 'sherpa-onnx AAR unexpectedly contains model data\n' >&2
    exit 1
fi

temp_output=$(mktemp "${module_dir}/build/sherpa-onnx.aar.XXXXXX")
trap 'rm -f "${temp_output}"' EXIT INT TERM
cp "${archive}" "${temp_output}"
mv "${temp_output}" "${module_dir}/build/sherpa-onnx.aar"
trap - EXIT INT TERM
