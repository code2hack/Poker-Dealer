use std::path::{Path, PathBuf};
use std::str::FromStr;

use serde::Serialize;
use tokio::process::Command;

use crate::error::BridgeError;
use crate::tmux::TmuxVersion;

#[derive(Debug, Serialize)]
pub struct DoctorReport {
    pub executable: PathBuf,
    pub tmux_version: String,
    pub supported: bool,
    pub minimum_version: &'static str,
}

pub async fn inspect_tmux(executable: &Path) -> Result<DoctorReport, BridgeError> {
    let output = Command::new(executable)
        .arg("-V")
        .output()
        .await
        .map_err(|source| BridgeError::TmuxExecution {
            path: executable.to_path_buf(),
            source,
        })?;

    if !output.status.success() {
        return Err(BridgeError::TmuxFailed(
            String::from_utf8_lossy(&output.stderr).trim().to_owned(),
        ));
    }

    let raw = String::from_utf8_lossy(&output.stdout);
    let version = TmuxVersion::from_str(&raw)?;
    if !version.is_supported() {
        return Err(BridgeError::UnsupportedTmux {
            found: version.to_string(),
        });
    }

    Ok(DoctorReport {
        executable: executable.to_path_buf(),
        tmux_version: version.to_string(),
        supported: true,
        minimum_version: "3.2",
    })
}

#[cfg(test)]
mod tests {
    use std::os::unix::fs::PermissionsExt;
    use std::path::Path;

    use tempfile::tempdir;
    use tokio::fs;

    use super::inspect_tmux;

    #[tokio::test]
    async fn invokes_tmux_as_an_argv_process_and_reads_version() {
        let directory = tempdir().expect("temporary directory");
        let executable = directory.path().join("fake tmux");
        fs::write(&executable, "#!/bin/sh\nprintf 'tmux 3.6a\\n'\n")
            .await
            .expect("write fake tmux");
        let mut permissions = fs::metadata(&executable)
            .await
            .expect("fake metadata")
            .permissions();
        permissions.set_mode(0o700);
        fs::set_permissions(&executable, permissions)
            .await
            .expect("make fake executable");

        let report = inspect_tmux(Path::new(&executable))
            .await
            .expect("doctor report");

        assert_eq!(report.tmux_version, "3.6a");
        assert!(report.supported);
    }
}
