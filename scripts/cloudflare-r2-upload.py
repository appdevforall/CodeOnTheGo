#!/usr/bin/env python3
"""Upload a file (e.g. APK) to Cloudflare R2. Credentials via env: CLOUDFLARE_ACCOUNT_ID, CLOUDFLARE_KEY_ID, CLOUDFLARE_SECRET_ACCESS_KEY."""
import sys
import os
import boto3
from botocore.config import Config

REQUIRED_ENV = (
    "CLOUDFLARE_ACCOUNT_ID",
    "CLOUDFLARE_KEY_ID",
    "CLOUDFLARE_SECRET_ACCESS_KEY",
)

for name in REQUIRED_ENV:
    if not os.environ.get(name):
        print(f"ERROR: {name} environment variable is not set.", file=sys.stderr)
        sys.exit(1)

CLOUDFLARE_ACCOUNT_ID = os.environ["CLOUDFLARE_ACCOUNT_ID"]
CLOUDFLARE_KEY_ID = os.environ["CLOUDFLARE_KEY_ID"]
CLOUDFLARE_SECRET_ACCESS_KEY = os.environ["CLOUDFLARE_SECRET_ACCESS_KEY"]
BUCKET_NAME = "apk-repo"

R2_ENDPOINT_URL = f"https://{CLOUDFLARE_ACCOUNT_ID}.r2.cloudflarestorage.com"

config = Config(
    read_timeout=300,
    connect_timeout=60,
    retries={"max_attempts": 10},
)

s3 = boto3.client(
    service_name="s3",
    endpoint_url=R2_ENDPOINT_URL,
    aws_access_key_id=CLOUDFLARE_KEY_ID,
    aws_secret_access_key=CLOUDFLARE_SECRET_ACCESS_KEY,
    region_name="auto",
    config=config,
)

if len(sys.argv) < 2:
    print("Usage: cloudflare-r2-upload.py <file_path>", file=sys.stderr)
    sys.exit(1)

file_path = sys.argv[1]
file_size = os.path.getsize(file_path)
file_name = os.path.basename(file_path)

extra_args = {}
if file_name.lower().endswith(".apk"):
    extra_args["ContentType"] = "application/vnd.android.package-archive"

# Progress callback: print new lines at 10% intervals for CI-friendly logs (bytes_amount is incremental per call)
_seen_so_far = [0]
_last_printed_pct = [-1]

def progress_callback(bytes_amount):
    _seen_so_far[0] += bytes_amount
    if file_size <= 0:
        return
    pct = int(100 * _seen_so_far[0] / file_size)
    if pct >= _last_printed_pct[0] + 10 or pct == 100:
        _last_printed_pct[0] = pct
        mb = _seen_so_far[0] / (1024 * 1024)
        total_mb = file_size / (1024 * 1024)
        print(f"Upload progress: {pct}% ({mb:.1f} MB / {total_mb:.1f} MB)", flush=True)


upload_kwargs = {"Callback": progress_callback}
if extra_args:
    upload_kwargs["ExtraArgs"] = extra_args

print(f"Uploading {file_name} ({file_size / (1024*1024):.1f} MB) to R2...", flush=True)
s3.upload_file(file_path, BUCKET_NAME, file_name, **upload_kwargs)
print("Upload complete.", flush=True)
