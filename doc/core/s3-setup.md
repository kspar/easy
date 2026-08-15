# Object storage for uploaded files

Uploaded images live in an S3 bucket. This is how to make one for an environment, and why each
setting is what it is — written from the dev bucket built on 2026-08-15, including the parts that
were wrong on the first attempt.

The design decisions behind it are on EZ-1571 (public bucket, unguessable keys) and EZ-1600 (no
reference columns, one nightly sweep). This file is the operating manual.

## There are two backends, and `local` is the default

| | |
|---|---|
| `local` | A directory. No AWS account, no credentials, no network. Laptops and CI. |
| `s3` | A bucket whose objects are publicly readable. Anything deployed. |

`easy.core.storage.backend` picks. The URL stored inside content is the same either way —
`/v2/resource/<key>/<filename>` on our own origin — and only the *serving* differs: `local` streams
the bytes, `s3` answers `302` to the object. That indirection is the whole reason a storage change
never has to rewrite a stored article, so **do not** put a bucket URL into content.

`doc/core/files-check.sh` works against both and says which one it found.

## Creating a bucket

```sh
B=lahendus-dev-files            # no dots: a dot breaks TLS on the virtual-host URL
aws s3api create-bucket --bucket $B --region eu-north-1 \
  --create-bucket-configuration LocationConstraint=eu-north-1

# ACLs off entirely. Public read comes from the bucket policy, never an object ACL.
aws s3api put-bucket-ownership-controls --bucket $B \
  --ownership-controls 'Rules=[{ObjectOwnership=BucketOwnerEnforced}]'

# The fiddly one. Keep the ACL blocks ON — we never use ACLs — and turn the POLICY blocks off so a
# public-read bucket policy is permitted at all.
aws s3api put-public-access-block --bucket $B --public-access-block-configuration \
  'BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=false,RestrictPublicBuckets=false'
```

Then the policy. It grants `GetObject` and nothing else:

```json
{"Version":"2012-10-17","Statement":[{
  "Sid":"PublicReadObjectsOnly","Effect":"Allow","Principal":"*",
  "Action":"s3:GetObject","Resource":"arn:aws:s3:::lahendus-dev-files/*"}]}
```

**The omission is the security model.** Keys are 160 CSPRNG bits precisely so that they cannot be
guessed; a public `ListBucket` would hand them out and the whole scheme collapses. Nothing else
protects a file — reads are unauthenticated by decision, so the key *is* the credential.

Two settings that matter by being absent:

- **Versioning stays off.** With it on, the nightly sweep's deletes leave delete markers and old
  versions: nothing is actually freed and it costs money quietly.
- **No CORS configuration.** Images load through an `<img>` tag via our `302`, which is not a CORS
  request. Adding a CORS policy would be cargo cult.

## IAM: one policy, one user per consumer

```json
{"Version":"2012-10-17","Statement":[
 {"Effect":"Allow","Action":["s3:PutObject","s3:GetObject","s3:DeleteObject"],
  "Resource":"arn:aws:s3:::lahendus-dev-files/*"},
 {"Effect":"Allow","Action":"s3:ListBucket","Resource":"arn:aws:s3:::lahendus-dev-files"}]}
```

`ListBucket` is there for the sweep's orphan pass — *our* listing, which is unrelated to denying
anonymous ones. No `s3:PutObjectAcl`: many recipes add it with `--acl public-read`, which fails
outright under `BucketOwnerEnforced`.

Dev has two users on this policy, `easy-dev-core` for the host and `easy-dev-test` for a laptop, so
revoking a developer's key does not break the environment and each key's use is separately
attributable.

## Credentials

Prefer the SDK's default chain — an instance role where the platform offers one, which needs no
stored credential at all. Where it does not (dev is a UT VM, not EC2), the key goes in
`/srv/easy/conf/secrets.yaml` beside the database password:

```yaml
easy:
  core:
    storage:
      s3:
        access-key: "..."
        secret-key: "..."
```

**Never in the Ansible-managed `application.yaml`.** `roles/core_config` greps the file it wrote for
credential-shaped keys and fails the run if it finds any, so this is enforced rather than agreed.
Ansible neither writes nor reads the secrets file; a human puts the value there once.

To install one without it passing through a terminal or a transcript: write
`aws iam create-access-key --output json` straight to a file, `scp` it, and merge it on the host with
a script that deletes the file afterwards.

## Wiring an environment

Role defaults in `ansible/roles/core_config/defaults/main.yml`; per-environment values in that
environment's vars:

```yaml
easy_core_storage_allow_s3: true          # its own opt-in, NOT easy_core_allow_real_outbound
easy_core_storage_backend: s3
easy_core_storage_s3_bucket: lahendus-dev-files
easy_core_storage_s3_region: eu-north-1
easy_core_storage_s3_public_base_url: https://lahendus-dev-files.s3.eu-north-1.amazonaws.com
easy_core_stored_file_sweep_delete: false # report-only until the logs have been read
```

**`easy_core_storage_allow_s3` is deliberately separate from `easy_core_allow_real_outbound`.** That
flag means "may reach systems that affect people we do not control" — a real gradebook, the IdP whose
accounts get deleted, students' inboxes — and it gates the assertions that enforce all three. Hanging
S3 off it would force an environment to switch those off to store a file: five checks disabled to buy
one feature.

**Every environment needs its own bucket.** The nightly sweep deletes objects that have no row in
*that* host's database, and dev's database is an anonymised copy of production — so anything uploaded
to production after the last import has no row on dev, and dev would collect it. Set
`easy_core_storage_prod_bucket` in an environment's **gitignored** inventory to arm the guard that
refuses production's bucket by name; it is empty by default because this repository is public.

**nginx** needs two things, both in `roles/nginx`: a `location /v2/resource/` proxy on the *web*
vhost, so the relative URLs in stored content resolve where web and API are separate origins, and a
larger `client_max_body_size` on `= /v2/files` alone.

## Verifying

The four properties the design rests on, none of which are visible from the config:

```sh
aws s3api put-object --bucket $B --key probeKEYprobeKEYprobeKEYaa --body px.png   # our user can write
curl -sI  "https://$B.s3.eu-north-1.amazonaws.com/probeKEYprobeKEYprobeKEYaa"     # anonymous GET  -> 200
curl -s   "https://$B.s3.eu-north-1.amazonaws.com/"                               # anonymous LIST -> AccessDenied
curl -sI -X PUT "https://$B.s3.eu-north-1.amazonaws.com/anon.png"                 # anonymous PUT  -> 403
```

Then end to end, against a core pointed at the bucket:

```sh
AWS_PROFILE=<profile> ./gradlew :core:bootRun --args='--server.port=8099 \
  --easy.core.storage.backend=s3 --easy.core.storage.s3.bucket=... --easy.core.storage.s3.public-base-url=...'
doc/core/files-check.sh
```

On a deployed host, without credentials to upload with, two requests still prove most of it:
`/v2/resource/<27 chars>/x.png` should answer **404** rather than 401 on the API origin — the
endpoint exists and is unauthenticated — and the same path on the *web* origin should answer 404
rather than the SPA's `index.html`, which proves the nginx location.

A newly installed key reports `ServiceName: N/A` from `aws iam get-access-key-last-used` until
something actually calls S3. The sweep's orphan pass calls `ListBucket` on its nightly run, so a
working credential proves itself by the next morning and a broken one is loud in the journal.
