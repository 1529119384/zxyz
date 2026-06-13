import { normalizePositiveId } from '@/utils/id'

/**
 * Apply a user profile response to stores and sync form state.
 * Shared across AccountSettings composables.
 */
export function applyProfileToStores(profile, { currentUserStore, teamStore, forms }) {
  if (!profile) return
  currentUserStore.setProfile(profile)
  if (forms.profileForm) {
    forms.profileForm.name = profile.name || ''
  }
  if (forms.contactForm) {
    forms.contactForm.email = profile.email || ''
    forms.contactForm.phone = profile.phone || ''
  }
  if (forms.teamForm) {
    forms.teamForm.defaultTeamId = normalizePositiveId(profile.defaultTeamId)
    teamStore.setDefaultTeam(forms.teamForm.defaultTeamId)
  }
}
