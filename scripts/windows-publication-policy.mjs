#!/usr/bin/env node
// Fail visibly when a ready Windows artifact cannot safely join the release.
if (process.env.WINDOWS_DOWNLOAD_OUTCOME === 'success') {
  const changed = process.env.ANDROID_CHANGED;
  const outcome = process.env.ANDROID_PUBLISH_OUTCOME || 'skipped';
  if (changed !== 'false' && outcome !== 'success') {
    console.error(`Windows publication blocked: Android publication is ${outcome}, but raw android_changed=${changed || 'unknown'} requires successful Android publication. Rerun CI with component=all (trimmed_experiment=false) so Android publishes first; for failed Android publication, fix its error before retrying. Windows-only builds do not bypass this safety gate.`);
    process.exitCode = 1;
  }
}