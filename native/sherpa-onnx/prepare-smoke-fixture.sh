#!/bin/sh
set -eu

if [ "$#" -ne 1 ]; then
    printf 'usage: %s <output-assets-directory>\n' "$0" >&2
    exit 2
fi

module_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo_dir=$(CDPATH= cd -- "${module_dir}/../.." && pwd)
output_dir=$1
. "${module_dir}/versions.env"

toolchain_dir="${repo_dir}/.toolchains/sherpa-onnx/smoke/${SMOKE_MODEL_NAME}"
archive="${toolchain_dir}/${SMOKE_MODEL_NAME}.tar.bz2"
mkdir -p "${toolchain_dir}"

if ! printf '%s  %s\n' "${SMOKE_MODEL_SHA256}" "${archive}" |
    sha256sum -c - >/dev/null 2>&1; then
    partial_archive="${archive}.part"
    curl -fL --http1.1 --retry 5 --retry-all-errors --connect-timeout 20 \
        -C - "${SMOKE_MODEL_URL}" -o "${partial_archive}"
    printf '%s  %s\n' "${SMOKE_MODEL_SHA256}" "${partial_archive}" | sha256sum -c -
    mv "${partial_archive}" "${archive}"
fi

printf '%s  %s\n' "${SMOKE_MODEL_SHA256}" "${archive}" | sha256sum -c -
if tar -tjf "${archive}" | grep -Eq '\.(so|dex|jar|class|java|kt)(/|$)'; then
    printf 'smoke model archive contains executable code\n' >&2
    exit 1
fi

extract_dir=$(mktemp -d "${toolchain_dir}/extract.XXXXXX")
trap 'rm -rf "${extract_dir}"' EXIT INT TERM
tar -xjf "${archive}" -C "${extract_dir}"
root_dir="${extract_dir}/${SMOKE_MODEL_NAME}"
test -d "${root_dir}"
encoder_path=$(find "${root_dir}" -type f -name 'encoder*.onnx' -print | sort | head -1)
decoder_path=$(find "${root_dir}" -type f -name 'decoder*.onnx' -print | sort | head -1)
joiner_path=$(find "${root_dir}" -type f -name 'joiner*.onnx' -print | sort | head -1)
tokens_path=$(find "${root_dir}" -type f -name 'tokens.txt' -print | sort | head -1)
sample_path=$(find "${root_dir}" -type f -iname '*.wav' -print | sort | head -1)
test -n "${encoder_path}"
test -n "${decoder_path}"
test -n "${joiner_path}"
test -n "${tokens_path}"
encoder_sha256=$(sha256sum "${encoder_path}" | cut -d ' ' -f1)
decoder_sha256=$(sha256sum "${decoder_path}" | cut -d ' ' -f1)
joiner_sha256=$(sha256sum "${joiner_path}" | cut -d ' ' -f1)
tokens_sha256=$(sha256sum "${tokens_path}" | cut -d ' ' -f1)

if [ -z "${sample_path}" ]; then
    temp_wav=$(mktemp "${toolchain_dir}/wav.XXXXXX")
    curl -fL --http1.1 --retry 5 --retry-all-errors --connect-timeout 20 \
        "${SMOKE_WAV_URL}" -o "${temp_wav}"
    printf '%s  %s\n' "${SMOKE_WAV_SHA256}" "${temp_wav}" | sha256sum -c -
    sample_path="${root_dir}/smoke.wav"
    cp "${temp_wav}" "${sample_path}"
    rm -f "${temp_wav}"
fi

rm -rf "${output_dir}"
mkdir -p "${output_dir}"
cp -R "${root_dir}" "${output_dir}/${SMOKE_MODEL_NAME}"
encoder_relative=${encoder_path#"${extract_dir}/"}
decoder_relative=${decoder_path#"${extract_dir}/"}
joiner_relative=${joiner_path#"${extract_dir}/"}
tokens_relative=${tokens_path#"${extract_dir}/"}
sample_relative=${sample_path#"${extract_dir}/"}
{
    printf 'packId=sherpa-smoke\n'
    printf 'packRevision=%s\n' "${SMOKE_MODEL_NAME}"
    printf 'adapter=PARAKEET_UNIFIED_STREAMING\n'
    printf 'encoder=%s\n' "${encoder_relative}"
    printf 'encoderSha256=%s\n' "${encoder_sha256}"
    printf 'decoder=%s\n' "${decoder_relative}"
    printf 'decoderSha256=%s\n' "${decoder_sha256}"
    printf 'joiner=%s\n' "${joiner_relative}"
    printf 'joinerSha256=%s\n' "${joiner_sha256}"
    printf 'tokens=%s\n' "${tokens_relative}"
    printf 'tokensSha256=%s\n' "${tokens_sha256}"
    printf 'sample=%s\n' "${sample_relative}"
} > "${output_dir}/sherpa-smoke.properties"
