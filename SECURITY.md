# Security Policy

## Supported Versions

| Version | Supported |
| --- | --- |
| 1.0.x | Yes |
| Earlier versions | No |

## Reporting a Vulnerability

**Do not open a public GitHub issue for security vulnerabilities.**

Report vulnerabilities through
[GitHub Private Vulnerability Reporting](https://github.com/messeb/volt/security/advisories/new).

Include:

- A description and potential impact
- Reproduction steps or a proof of concept
- Affected versions and environment
- A suggested fix, if available

We aim to acknowledge reports within 48 hours and release a patch within 14
days for confirmed critical issues. Timelines may vary while the project is
pre-1.0, but reporters will receive status updates.

## Security Best Practices

- Never log API keys, bearer tokens, or other credentials.
- Enforce proxy-level request limits for Internet-facing deployments.
- Keep Maven and GitHub Actions dependencies updated.
- Use environment variables or GitHub secrets for credentials.
- Enable two-factor authentication for repository access.

