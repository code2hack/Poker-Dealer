use std::fmt::{Display, Formatter};
use std::str::FromStr;

use crate::error::BridgeError;

pub const MINIMUM_TMUX_VERSION: (u32, u32) = (3, 2);

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct TmuxVersion {
    raw: String,
    major: u32,
    minor: u32,
}

impl TmuxVersion {
    #[must_use]
    pub fn is_supported(&self) -> bool {
        (self.major, self.minor) >= MINIMUM_TMUX_VERSION
    }
}

impl Display for TmuxVersion {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(&self.raw)
    }
}

impl FromStr for TmuxVersion {
    type Err = BridgeError;

    fn from_str(value: &str) -> Result<Self, Self::Err> {
        let trimmed = value.trim();
        let version = trimmed
            .strip_prefix("tmux ")
            .ok_or_else(|| BridgeError::InvalidTmuxVersion(trimmed.to_owned()))?
            .strip_prefix("next-")
            .unwrap_or_else(|| trimmed.strip_prefix("tmux ").expect("prefix checked"));
        let (major_text, remainder) = version
            .split_once('.')
            .ok_or_else(|| BridgeError::InvalidTmuxVersion(trimmed.to_owned()))?;
        let minor_text: String = remainder.chars().take_while(char::is_ascii_digit).collect();
        if minor_text.is_empty() {
            return Err(BridgeError::InvalidTmuxVersion(trimmed.to_owned()));
        }

        let major = major_text
            .parse()
            .map_err(|_| BridgeError::InvalidTmuxVersion(trimmed.to_owned()))?;
        let minor = minor_text
            .parse()
            .map_err(|_| BridgeError::InvalidTmuxVersion(trimmed.to_owned()))?;

        Ok(Self {
            raw: version.to_owned(),
            major,
            minor,
        })
    }
}

#[cfg(test)]
mod tests {
    use std::str::FromStr;

    use super::TmuxVersion;

    #[test]
    fn parses_release_and_letter_suffix_versions() {
        let minimum = TmuxVersion::from_str("tmux 3.2").expect("valid minimum version");
        let current = TmuxVersion::from_str("tmux 3.6a\n").expect("valid suffix version");

        assert!(minimum.is_supported());
        assert!(current.is_supported());
        assert_eq!(current.to_string(), "3.6a");
    }

    #[test]
    fn rejects_versions_below_minimum() {
        let old = TmuxVersion::from_str("tmux 3.1c").expect("syntactically valid version");

        assert!(!old.is_supported());
    }

    #[test]
    fn rejects_human_text_that_is_not_tmux_version_output() {
        assert!(TmuxVersion::from_str("version 3.6").is_err());
        assert!(TmuxVersion::from_str("tmux unknown").is_err());
    }
}
