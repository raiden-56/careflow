'use client';

import { Suspense, useEffect, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { Box, Card, Tab, Tabs } from '@mui/material';
import { useAuth } from '@/lib/auth/AuthProvider';
import { PageHeader } from '@/components/ui/PageHeader';
import { HospitalSettingsTab } from '@/components/admin/settings/HospitalSettingsTab';
import { TemplatesTab } from '@/components/admin/settings/TemplatesTab';
import { EscalationsTab, ThresholdsTab } from '@/components/admin/settings/RulesTabs';
import { DischargeChecklistTab, IntegrationsTab } from '@/components/admin/settings/DischargeAndIntegrationTabs';

const TABS = [
  { value: 'hospital', label: 'Hospital & hours' },
  { value: 'templates', label: 'Notification templates' },
  { value: 'escalations', label: 'Escalation rules' },
  { value: 'thresholds', label: 'Observation thresholds' },
  { value: 'discharge', label: 'Discharge checklist' },
  { value: 'integrations', label: 'Integrations', permission: 'INTEGRATION_MANAGE' },
] as const;

export default function SettingsPage() {
  return (
    <Suspense>
      <SettingsContent />
    </Suspense>
  );
}

function SettingsContent() {
  const router = useRouter();
  const params = useSearchParams();
  const { hasPermission } = useAuth();
  const tabs = TABS.filter((t) => !('permission' in t) || hasPermission(t.permission));
  const initial = params.get('tab');
  const [tab, setTab] = useState<string>(tabs.some((t) => t.value === initial) ? initial! : 'hospital');
  useEffect(() => {
    const p = params.get('tab');
    if (p && p !== tab && tabs.some((t) => t.value === p)) setTab(p);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [params]);
  return (
    <>
      <PageHeader
        title="Settings & rules"
        subtitle="Hospital-wide configuration. Every change is validated on the server and recorded in the audit log."
        breadcrumbs={[{ label: 'Administration', href: '/admin' }, { label: 'Settings & rules' }]}
      />
      <Card sx={{ mb: 5 }}>
        <Tabs
          value={tab}
          onChange={(_, v) => {
            setTab(v);
            router.replace(`/admin/settings?tab=${v}`, { scroll: false });
          }}
          variant="scrollable"
          scrollButtons="auto"
          allowScrollButtonsMobile
          sx={{ px: 2 }}
          aria-label="Settings sections"
        >
          {tabs.map((t) => (
            <Tab key={t.value} value={t.value} label={t.label} />
          ))}
        </Tabs>
      </Card>
      <Box>
        {tab === 'hospital' && <HospitalSettingsTab />}
        {tab === 'templates' && <TemplatesTab />}
        {tab === 'escalations' && <EscalationsTab />}
        {tab === 'thresholds' && <ThresholdsTab />}
        {tab === 'discharge' && <DischargeChecklistTab />}
        {tab === 'integrations' && <IntegrationsTab />}
      </Box>
    </>
  );
}
