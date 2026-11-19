/**
 * Native overlay search bridge. Uses existing Atlas auth token only at activation time.
 * The Java foreground service never persists the token to disk.
 */
import { Capacitor, registerPlugin } from '@capacitor/core';

export interface OverlayStatus {
  supported: boolean;
  permissionGranted: boolean;
  running: boolean;
  opacity: number;
}
interface AtlasOverlayBridge {
  status(): Promise<OverlayStatus>;
  requestPermission(): Promise<OverlayStatus>;
  start(options: { token: string }): Promise<OverlayStatus>;
  stop(): Promise<OverlayStatus>;
  setOpacity(options: { opacity: number }): Promise<OverlayStatus>;
  openSource(options: { sourceId: string; sourceName: string; url: string }): Promise<void>;
}

const plugin = registerPlugin<AtlasOverlayBridge>('AtlasOverlay');

export const atlasOverlay = {
  available: () => Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android',
  status: () => plugin.status(),
  requestPermission: () => plugin.requestPermission(),
  start: (token: string) => plugin.start({ token }),
  stop: () => plugin.stop(),
  setOpacity: (opacity: number) => plugin.setOpacity({ opacity }),
  openSource: (source: { sourceId: string; sourceName: string; url: string }) =>
    plugin.openSource(source),
};
