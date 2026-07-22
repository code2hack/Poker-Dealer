use std::path::PathBuf;

use clap::{Parser, Subcommand};

#[derive(Debug, Parser)]
#[command(name = "poker-dealer-bridge")]
#[command(about = "Constrained, authenticated bridge to selected tmux panes")]
#[command(version)]
pub struct Cli {
    #[command(subcommand)]
    pub command: BridgeCommand,
}

#[derive(Debug, Subcommand)]
pub enum BridgeCommand {
    /// Start the bridge daemon (enabled when the secure M1 transport lands).
    Serve,
    /// Create pairing material (enabled with the secure M1 identity store).
    Pair,
    /// Check local prerequisites without modifying any pane.
    Doctor {
        #[arg(long, default_value = "tmux")]
        tmux: PathBuf,
    },
    /// Report configured tmux servers (enabled with M1 configuration).
    ListServers,
}
