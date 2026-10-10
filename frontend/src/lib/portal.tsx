'use client';

import { createContext, ReactNode, useContext, useEffect, useMemo, useState } from 'react';
import { MenuItem, TextField } from '@mui/material';
import type { PatientLink } from './api/types';
import { useAuth } from './auth/AuthProvider';

interface PortalContextValue {
  /** The patient whose records are being viewed (self or an authorized dependant). */
  patientId: string | undefined;
  patient: PatientLink | undefined;
  patients: PatientLink[];
  setPatientId: (id: string) => void;
  canSeeClinical: boolean;
  canSeeBilling: boolean;
}

const PortalContext = createContext<PortalContextValue | null>(null);
const KEY = 'careflow.portal.patient';

/** Patient-portal scope: which accessible patient the signed-in user is currently viewing. */
export function PortalPatientProvider({ children }: { children: ReactNode }) {
  const { me } = useAuth();
  const patients = useMemo(() => me?.accessiblePatients ?? [], [me]);
  const [patientId, setPatientIdState] = useState<string | undefined>(undefined);

  useEffect(() => {
    if (!patients.length) return;
    let stored: string | null = null;
    try {
      stored = localStorage.getItem(KEY);
    } catch {
      // ignore
    }
    const valid = patients.find((p) => p.patientId === stored);
    setPatientIdState(valid ? valid.patientId : patients[0].patientId);
  }, [patients]);

  const value = useMemo<PortalContextValue>(() => {
    const patient = patients.find((p) => p.patientId === patientId);
    return {
      patientId,
      patient,
      patients,
      setPatientId: (id) => {
        setPatientIdState(id);
        try {
          localStorage.setItem(KEY, id);
        } catch {
          // ignore
        }
      },
      canSeeClinical: !!patient && patient.scope !== 'BILLING_ONLY',
      canSeeBilling: !!patient && patient.scope !== 'CLINICAL_ONLY',
    };
  }, [patientId, patients]);

  return <PortalContext.Provider value={value}>{children}</PortalContext.Provider>;
}

export function usePortal(): PortalContextValue {
  const ctx = useContext(PortalContext);
  if (!ctx) throw new Error('usePortal must be used under /patient');
  return ctx;
}

/** Dropdown shown on portal pages when the user can view more than one patient. */
export function PortalPatientSwitcher() {
  const { patients, patientId, setPatientId } = usePortal();
  if (patients.length < 2) return null;
  return (
    <TextField
      select
      size="small"
      label="Viewing records for"
      value={patientId ?? ''}
      onChange={(e) => setPatientId(e.target.value)}
      sx={{ minWidth: 240 }}
    >
      {patients.map((p) => (
        <MenuItem key={p.patientId} value={p.patientId}>
          {p.name} {p.relationship === 'SELF' ? '(you)' : `(${p.relationship})`}
        </MenuItem>
      ))}
    </TextField>
  );
}
