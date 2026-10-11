'use client';

import { createContext, ReactNode, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { Button } from '@mui/material';
import { closeSnackbar, enqueueSnackbar } from 'notistack';

/** The beforeinstallprompt event (Chromium only; not yet in the TypeScript DOM types). */
interface InstallPromptEvent extends Event {
  prompt: () => Promise<void>;
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>;
}

interface PwaState {
  /** The device has no network connection. */
  offline: boolean;
  /** The browser offers an install prompt and the app is not installed yet. */
  canInstall: boolean;
  /** Running as an installed app (standalone window). */
  installed: boolean;
  install: () => Promise<void>;
}

const PwaContext = createContext<PwaState>({ offline: false, canInstall: false, installed: false, install: async () => {} });

export const usePwa = () => useContext(PwaContext);

/**
 * Registers the service worker (production builds only, so development never serves stale assets),
 * offers a reload when a new version is ready, and tracks connectivity and the install prompt.
 */
export function PwaProvider({ children }: { children: ReactNode }) {
  const [offline, setOffline] = useState(false);
  const [installPrompt, setInstallPrompt] = useState<InstallPromptEvent | null>(null);
  const [installed, setInstalled] = useState(false);
  // Set when the user accepts an update; only then may a controller change reload the page (the first install
  // also changes controller, and must not reload a page the user is working in).
  const updateAccepted = useRef(false);

  useEffect(() => {
    setOffline(!navigator.onLine);
    const on = () => setOffline(false);
    const off = () => setOffline(true);
    window.addEventListener('online', on);
    window.addEventListener('offline', off);
    return () => {
      window.removeEventListener('online', on);
      window.removeEventListener('offline', off);
    };
  }, []);

  useEffect(() => {
    const standalone = window.matchMedia('(display-mode: standalone)');
    setInstalled(standalone.matches);
    const onPrompt = (e: Event) => {
      e.preventDefault();
      setInstallPrompt(e as InstallPromptEvent);
    };
    const onInstalled = () => {
      setInstallPrompt(null);
      setInstalled(true);
    };
    window.addEventListener('beforeinstallprompt', onPrompt);
    window.addEventListener('appinstalled', onInstalled);
    return () => {
      window.removeEventListener('beforeinstallprompt', onPrompt);
      window.removeEventListener('appinstalled', onInstalled);
    };
  }, []);

  useEffect(() => {
    if (process.env.NODE_ENV !== 'production' || !('serviceWorker' in navigator)) return;

    const offerUpdate = (worker: ServiceWorker) => {
      enqueueSnackbar('A new version of CareFlow is ready.', {
        variant: 'info',
        persist: true,
        preventDuplicate: true,
        key: 'careflow-update',
        action: (key) => (
          <Button
            color="inherit"
            size="small"
            onClick={() => {
              closeSnackbar(key);
              updateAccepted.current = true;
              worker.postMessage('SKIP_WAITING');
            }}
          >
            Reload
          </Button>
        ),
      });
    };

    const onControllerChange = () => {
      if (!updateAccepted.current) return;
      updateAccepted.current = false;
      window.location.reload();
    };

    let interval: number | undefined;
    navigator.serviceWorker
      .register('/sw.js', { scope: '/' })
      .then((reg) => {
        // A worker already waiting means an update arrived while no tab was open.
        if (reg.waiting && navigator.serviceWorker.controller) offerUpdate(reg.waiting);
        reg.addEventListener('updatefound', () => {
          const worker = reg.installing;
          worker?.addEventListener('statechange', () => {
            // Only an update (not the first install) needs the user to reload.
            if (worker.state === 'installed' && navigator.serviceWorker.controller) offerUpdate(worker);
          });
        });
        // Long-running ward sessions: check for a new release every 30 minutes.
        interval = window.setInterval(() => reg.update().catch(() => {}), 30 * 60_000);
      })
      .catch(() => {
        // Registration failure only disables offline/install support; the app keeps working.
      });
    navigator.serviceWorker.addEventListener('controllerchange', onControllerChange);
    return () => {
      window.clearInterval(interval);
      navigator.serviceWorker.removeEventListener('controllerchange', onControllerChange);
    };
  }, []);

  const install = useCallback(async () => {
    if (!installPrompt) return;
    await installPrompt.prompt();
    await installPrompt.userChoice;
    setInstallPrompt(null);
  }, [installPrompt]);

  const value = useMemo(
    () => ({ offline, canInstall: !!installPrompt && !installed, installed, install }),
    [offline, installPrompt, installed, install],
  );
  return <PwaContext.Provider value={value}>{children}</PwaContext.Provider>;
}
