# Security Policy

## Reporting a Vulnerability

If you discover a security vulnerability in Iceberg Lens, please report it responsibly.

**Do not open a public GitHub issue for security vulnerabilities.**

Instead, use GitHub's built-in private vulnerability reporting:

1. Go to the [Security tab](https://github.com/mmdemirbas/ice-lens/security) of this repository.
2. Click "Report a vulnerability".
3. Provide a description of the vulnerability, steps to reproduce, and any potential impact.

You will receive a response acknowledging your report. We will work with you to understand the issue and coordinate a fix before any public disclosure.

## Scope

Iceberg Lens is a read-only desktop application that runs locally. It does not accept network connections, does not have a web interface, and does not modify Iceberg tables. The primary security surface is:

- File path handling (path traversal)
- DuckDB SQL query construction (injection via crafted file paths)
- Avro/JSON deserialization (malformed metadata files)
