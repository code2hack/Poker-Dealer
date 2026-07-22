use std::path::PathBuf;

use thiserror::Error;

#[derive(Debug, Error)]
pub enum BridgeError {
    #[error("failed to execute tmux at {path}: {source}")]
    TmuxExecution {
        path: PathBuf,
        #[source]
        source: std::io::Error,
    },
    #[error("tmux exited unsuccessfully: {0}")]
    TmuxFailed(String),
    #[error("invalid tmux version output: {0}")]
    InvalidTmuxVersion(String),
    #[error("tmux {found} is unsupported; Poker-Dealer requires tmux 3.2 or newer")]
    UnsupportedTmux { found: String },
    #[error("{0} is not implemented until the secure M1 transport is complete")]
    MilestoneUnavailable(&'static str),
    #[error("failed to serialize command output: {0}")]
    Serialization(#[from] serde_json::Error),
}
