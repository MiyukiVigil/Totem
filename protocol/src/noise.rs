//! Noise handshakes. Everything uses `Noise_IK_25519_ChaChaPoly_SHA256`, which is supported by
//! both `snow` (Rust) and Signal's noise-java (Kotlin). noise-java has no `psk` modifiers, which
//! is why pairing does not use `XXpsk3`.
//!
//! - **Pairing:** the PC shows a QR code with its static public key and a one-time 32-byte
//!   pairing secret. The phone (initiator) runs IK towards that key and sends the secret as the
//!   payload of the first message; it is encrypted to the PC's static key, so only the PC can
//!   read it. The PC checks it with [`pairing_secret_matches`] and learns the phone's static key
//!   from the handshake. Both screens can also show [`confirmation_code`].
//! - **Session:** the PC (initiator) already knows the phone's static key from pairing and opens
//!   a fresh session for each burst of requests. The phone (responder) must check the PC's static
//!   key against its list of paired devices once the handshake is done.
//!
//! Pairing and sessions use different prologues, so a message from one can never be accepted as
//! the other, and a peer speaking another protocol version fails the handshake.

pub use snow::{HandshakeState, TransportState};

pub const PARAMS: &str = "Noise_IK_25519_ChaChaPoly_SHA256";
pub const PAIRING_PROLOGUE: &[u8] = b"Totem/1 pairing";
pub const SESSION_PROLOGUE: &[u8] = b"Totem/1 session";
pub const PAIRING_SECRET_LEN: usize = 32;
/// Noise's maximum message size.
pub const MAX_MESSAGE_LEN: usize = 65535;

pub struct Keypair {
    pub private: Vec<u8>,
    pub public: Vec<u8>,
}

pub fn generate_keypair() -> Result<Keypair, snow::Error> {
    let kp = snow::Builder::new(PARAMS.parse()?).generate_keypair()?;
    Ok(Keypair { private: kp.private, public: kp.public })
}

fn initiator(prologue: &[u8], local_private: &[u8], remote_public: &[u8]) -> Result<HandshakeState, snow::Error> {
    snow::Builder::new(PARAMS.parse()?)
        .prologue(prologue)
        .local_private_key(local_private)
        .remote_public_key(remote_public)
        .build_initiator()
}

fn responder(prologue: &[u8], local_private: &[u8]) -> Result<HandshakeState, snow::Error> {
    snow::Builder::new(PARAMS.parse()?)
        .prologue(prologue)
        .local_private_key(local_private)
        .build_responder()
}

/// Phone side of pairing. `pc_public` comes from the QR code.
pub fn pairing_initiator(phone_private: &[u8], pc_public: &[u8]) -> Result<HandshakeState, snow::Error> {
    initiator(PAIRING_PROLOGUE, phone_private, pc_public)
}

/// PC side of pairing.
pub fn pairing_responder(pc_private: &[u8]) -> Result<HandshakeState, snow::Error> {
    responder(PAIRING_PROLOGUE, pc_private)
}

/// PC side of a session. `phone_public` was learned during pairing.
pub fn session_initiator(pc_private: &[u8], phone_public: &[u8]) -> Result<HandshakeState, snow::Error> {
    initiator(SESSION_PROLOGUE, pc_private, phone_public)
}

/// Phone side of a session.
pub fn session_responder(phone_private: &[u8]) -> Result<HandshakeState, snow::Error> {
    responder(SESSION_PROLOGUE, phone_private)
}

/// Constant-time check of the pairing secret the phone sent against the one in the QR code.
pub fn pairing_secret_matches(expected: &[u8], received: &[u8]) -> bool {
    expected.len() == PAIRING_SECRET_LEN
        && received.len() == PAIRING_SECRET_LEN
        && expected.iter().zip(received).fold(0u8, |acc, (a, b)| acc | (a ^ b)) == 0
}

/// Six-digit code both screens show after pairing, from the first 4 bytes of the handshake hash.
/// If anything about the handshake differed between the two sides, so do the codes.
pub fn confirmation_code(handshake_hash: &[u8]) -> String {
    let n = u32::from_be_bytes([handshake_hash[0], handshake_hash[1], handshake_hash[2], handshake_hash[3]]);
    format!("{:06}", n % 1_000_000)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Runs a two-message IK handshake. Returns both states and the payload the responder read
    /// from the first message.
    fn run(
        mut i: HandshakeState,
        mut r: HandshakeState,
        first_payload: &[u8],
    ) -> Result<(HandshakeState, HandshakeState, Vec<u8>), snow::Error> {
        let mut msg = vec![0u8; MAX_MESSAGE_LEN];
        let mut payload = vec![0u8; MAX_MESSAGE_LEN];
        let n = i.write_message(first_payload, &mut msg)?;
        let p = r.read_message(&msg[..n], &mut payload)?;
        let received = payload[..p].to_vec();
        let n = r.write_message(&[], &mut msg)?;
        i.read_message(&msg[..n], &mut payload)?;
        assert!(i.is_handshake_finished() && r.is_handshake_finished());
        Ok((i, r, received))
    }

    #[test]
    fn pairing_delivers_secret_and_codes_match() {
        let phone = generate_keypair().unwrap();
        let pc = generate_keypair().unwrap();
        let secret = [7u8; PAIRING_SECRET_LEN];
        let (p, c, got) = run(
            pairing_initiator(&phone.private, &pc.public).unwrap(),
            pairing_responder(&pc.private).unwrap(),
            &secret,
        )
        .unwrap();
        assert!(pairing_secret_matches(&secret, &got));
        assert_eq!(c.get_remote_static().unwrap(), phone.public.as_slice());
        assert_eq!(confirmation_code(p.get_handshake_hash()), confirmation_code(c.get_handshake_hash()));
    }

    #[test]
    fn pairing_secret_check_rejects_wrong_or_short_secret() {
        let secret = [7u8; PAIRING_SECRET_LEN];
        let mut wrong = secret;
        wrong[31] ^= 1;
        assert!(!pairing_secret_matches(&secret, &wrong));
        assert!(!pairing_secret_matches(&secret, &secret[..31]));
        assert!(!pairing_secret_matches(&[], &[]));
    }

    #[test]
    fn pairing_fails_if_qr_key_is_not_the_pcs() {
        let phone = generate_keypair().unwrap();
        let pc = generate_keypair().unwrap();
        let other = generate_keypair().unwrap();
        let result = run(
            pairing_initiator(&phone.private, &other.public).unwrap(),
            pairing_responder(&pc.private).unwrap(),
            &[7u8; PAIRING_SECRET_LEN],
        );
        assert!(result.is_err());
    }

    #[test]
    fn session_fails_if_pc_has_wrong_phone_key() {
        let pc = generate_keypair().unwrap();
        let phone = generate_keypair().unwrap();
        let impostor = generate_keypair().unwrap();
        let result = run(
            session_initiator(&pc.private, &impostor.public).unwrap(),
            session_responder(&phone.private).unwrap(),
            &[],
        );
        assert!(result.is_err());
    }

    #[test]
    fn pairing_message_is_not_accepted_as_session() {
        let phone = generate_keypair().unwrap();
        let pc = generate_keypair().unwrap();
        // Same keys, but the pairing prologue on one side and the session prologue on the other.
        let result = run(
            pairing_initiator(&phone.private, &pc.public).unwrap(),
            session_responder(&pc.private).unwrap(),
            &[],
        );
        assert!(result.is_err());
    }

    #[test]
    fn transport_rejects_replayed_message() {
        let pc = generate_keypair().unwrap();
        let phone = generate_keypair().unwrap();
        let (i, r, _) = run(
            session_initiator(&pc.private, &phone.public).unwrap(),
            session_responder(&phone.private).unwrap(),
            &[],
        )
        .unwrap();
        let mut i = i.into_transport_mode().unwrap();
        let mut r = r.into_transport_mode().unwrap();
        let mut ct = vec![0u8; 1024];
        let mut pt = vec![0u8; 1024];
        let n = i.write_message(b"get_code", &mut ct).unwrap();
        let m = r.read_message(&ct[..n], &mut pt).unwrap();
        assert_eq!(&pt[..m], b"get_code");
        assert!(r.read_message(&ct[..n], &mut pt).is_err(), "replay must fail");
    }
}
