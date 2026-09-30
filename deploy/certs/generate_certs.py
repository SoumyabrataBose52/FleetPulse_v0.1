"""
§10.1 & §15.5 Private CA and mTLS Certificate Generator.

Generates self-signed Root CA, Gateway server certificate/keystore,
and OEM client certificates for Astra, Borealis, Cetus, Draco, Echo,
plus an untrusted rogue certificate for test verification.
"""

from __future__ import annotations

import datetime
from pathlib import Path
from cryptography import x509
from cryptography.hazmat.backends import default_backend
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives.serialization import pkcs12
from cryptography.x509.oid import NameOID


CERTS_DIR = Path(__file__).resolve().parent


def generate_key() -> rsa.RSAPrivateKey:
    return rsa.generate_private_key(
        public_exponent=65537,
        key_size=2048,
        backend=default_backend(),
    )


def generate_ca(key: rsa.RSAPrivateKey, common_name: str) -> x509.Certificate:
    subject = issuer = x509.Name([
        x509.NameAttribute(NameOID.COUNTRY_NAME, "IN"),
        x509.NameAttribute(NameOID.ORGANIZATION_NAME, "FleetPulse"),
        x509.NameAttribute(NameOID.COMMON_NAME, common_name),
    ])
    now = datetime.datetime.now(datetime.timezone.utc)
    return (
        x509.CertificateBuilder()
        .subject_name(subject)
        .issuer_name(issuer)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - datetime.timedelta(days=1))
        .not_valid_after(now + datetime.timedelta(days=3650))
        .add_extension(x509.BasicConstraints(ca=True, path_length=None), critical=True)
        .sign(key, hashes.SHA256(), default_backend())
    )


def generate_cert(
    cert_key: rsa.RSAPrivateKey,
    ca_cert: x509.Certificate,
    ca_key: rsa.RSAPrivateKey,
    common_name: str,
    san_dns: list[str] | None = None,
) -> x509.Certificate:
    subject = x509.Name([
        x509.NameAttribute(NameOID.COUNTRY_NAME, "IN"),
        x509.NameAttribute(NameOID.ORGANIZATION_NAME, "FleetPulse"),
        x509.NameAttribute(NameOID.COMMON_NAME, common_name),
    ])
    now = datetime.datetime.now(datetime.timezone.utc)
    builder = (
        x509.CertificateBuilder()
        .subject_name(subject)
        .issuer_name(ca_cert.subject)
        .public_key(cert_key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - datetime.timedelta(days=1))
        .not_valid_after(now + datetime.timedelta(days=365))
        .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
    )
    if san_dns:
        names = [x509.DNSName(d) for d in san_dns]
        builder = builder.add_extension(x509.SubjectAlternativeName(names), critical=False)

    return builder.sign(ca_key, hashes.SHA256(), default_backend())


def save_pem(path: Path, data: bytes):
    path.write_bytes(data)


def main():
    CERTS_DIR.mkdir(parents=True, exist_ok=True)
    print("Generating FleetPulse Private CA...")

    # 1. FleetPulse Root CA
    ca_key = generate_key()
    ca_cert = generate_ca(ca_key, "FleetPulse Root CA")
    save_pem(
        CERTS_DIR / "ca.key",
        ca_key.private_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PrivateFormat.TraditionalOpenSSL,
            encryption_algorithm=serialization.NoEncryption(),
        ),
    )
    save_pem(CERTS_DIR / "ca.crt", ca_cert.public_bytes(serialization.Encoding.PEM))

    # 2. Gateway Server Certificate
    server_key = generate_key()
    server_cert = generate_cert(
        server_key, ca_cert, ca_key, "gateway.fleetpulse.local", ["localhost", "gateway", "127.0.0.1"]
    )
    save_pem(
        CERTS_DIR / "server.key",
        server_key.private_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PrivateFormat.TraditionalOpenSSL,
            encryption_algorithm=serialization.NoEncryption(),
        ),
    )
    save_pem(CERTS_DIR / "server.crt", server_cert.public_bytes(serialization.Encoding.PEM))

    # 3. OEM Client Certificates (A..E)
    for oem in ["a", "b", "c", "d", "e"]:
        c_key = generate_key()
        c_cert = generate_cert(c_key, ca_cert, ca_key, f"oem-client-{oem}")
        save_pem(
            CERTS_DIR / f"client-oem-{oem}.key",
            c_key.private_bytes(
                encoding=serialization.Encoding.PEM,
                format=serialization.PrivateFormat.TraditionalOpenSSL,
                encryption_algorithm=serialization.NoEncryption(),
            ),
        )
        save_pem(CERTS_DIR / f"client-oem-{oem}.crt", c_cert.public_bytes(serialization.Encoding.PEM))

    # 4. Untrusted Rogue Certificate (Signed by a fake attacker CA)
    rogue_ca_key = generate_key()
    rogue_ca_cert = generate_ca(rogue_ca_key, "Rogue Attacker CA")
    untrusted_key = generate_key()
    untrusted_cert = generate_cert(untrusted_key, rogue_ca_cert, rogue_ca_key, "untrusted-attacker")
    save_pem(
        CERTS_DIR / "untrusted.key",
        untrusted_key.private_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PrivateFormat.TraditionalOpenSSL,
            encryption_algorithm=serialization.NoEncryption(),
        ),
    )
    save_pem(CERTS_DIR / "untrusted.crt", untrusted_cert.public_bytes(serialization.Encoding.PEM))

    # 5. PKCS12 Keystore & Truststore for Spring Boot
    keystore_data = pkcs12.serialize_key_and_certificates(
        name=b"gateway",
        key=server_key,
        cert=server_cert,
        cas=[ca_cert],
        encryption_algorithm=serialization.BestAvailableEncryption(b"changeit"),
    )
    (CERTS_DIR / "keystore.p12").write_bytes(keystore_data)

    truststore_data = pkcs12.serialize_key_and_certificates(
        name=b"fleetpulse-ca",
        key=None,
        cert=ca_cert,
        cas=None,
        encryption_algorithm=serialization.BestAvailableEncryption(b"changeit"),
    )
    (CERTS_DIR / "truststore.p12").write_bytes(truststore_data)

    print("All TLS certificates and PKCS12 stores created successfully in deploy/certs/.")


if __name__ == "__main__":
    main()
