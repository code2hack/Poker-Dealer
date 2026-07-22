use clap::Parser;
use poker_dealer_bridge::cli::{BridgeCommand, Cli};
use poker_dealer_bridge::doctor::inspect_tmux;
use poker_dealer_bridge::error::BridgeError;
use tracing_subscriber::EnvFilter;

#[tokio::main]
async fn main() -> Result<(), BridgeError> {
    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info")),
        )
        .with_target(false)
        .init();

    let cli = Cli::parse();
    match cli.command {
        BridgeCommand::Doctor { tmux } => {
            let report = inspect_tmux(&tmux).await?;
            println!("{}", serde_json::to_string_pretty(&report)?);
            Ok(())
        }
        BridgeCommand::Serve => Err(BridgeError::MilestoneUnavailable("serve")),
        BridgeCommand::Pair => Err(BridgeError::MilestoneUnavailable("pair")),
        BridgeCommand::ListServers => Err(BridgeError::MilestoneUnavailable("list-servers")),
    }
}
