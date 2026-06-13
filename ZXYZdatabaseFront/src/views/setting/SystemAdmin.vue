<template>
  <div class="settings-grid admin-grid">
    <SystemMessagePanel />
    <EmailServerPanel />
    <EmailRecordPanel />
    <DatabaseMaintenancePanel />
    <AdminTeamPanel />
  </div>
</template>

<script setup>
import AdminTeamPanel from './components/AdminTeamPanel.vue'
import DatabaseMaintenancePanel from './components/DatabaseMaintenancePanel.vue'
import EmailRecordPanel from './components/EmailRecordPanel.vue'
import EmailServerPanel from './components/EmailServerPanel.vue'
import SystemMessagePanel from './components/SystemMessagePanel.vue'
</script>
