#!/bin/sh
set -eu

alias_name='alias/accord-foundation-local'

if awslocal kms describe-key --key-id "$alias_name" >/dev/null 2>&1; then
  exit 0
fi

key_id="$(awslocal kms create-key \
  --description 'Accord Foundation local envelope encryption key' \
  --key-usage ENCRYPT_DECRYPT \
  --origin AWS_KMS \
  --query 'KeyMetadata.KeyId' \
  --output text)"

test -n "$key_id"
test "$key_id" != 'None'
awslocal kms create-alias --alias-name "$alias_name" --target-key-id "$key_id"
awslocal kms describe-key --key-id "$alias_name" >/dev/null
