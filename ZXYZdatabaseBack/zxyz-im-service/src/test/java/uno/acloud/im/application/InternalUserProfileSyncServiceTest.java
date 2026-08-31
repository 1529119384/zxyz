package uno.acloud.im.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.im.infrastructure.persistence.entity.UserProfile;
import uno.acloud.im.infrastructure.mapper.UserProfileMapper;
import uno.acloud.im.dto.InternalUserProfileSyncRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class InternalUserProfileSyncServiceTest {

    @Mock
    private UserProfileMapper userProfileMapper;

    @Test
    void syncUserProfileShouldUpsertProfile() {
        InternalUserProfileSyncService service = new InternalUserProfileSyncService(userProfileMapper);
        InternalUserProfileSyncRequest request = new InternalUserProfileSyncRequest();
        request.setUserId(7L);
        request.setUsername("admin");
        request.setName("管理员");
        request.setEmail("admin@example.com");
        request.setAvatar("https://demo-bucket.oss-cn-shenzhen.aliyuncs.com/avatar/admin.png");

        service.syncUserProfile(request);

        ArgumentCaptor<UserProfile> profileCaptor = ArgumentCaptor.forClass(UserProfile.class);
        verify(userProfileMapper).upsert(profileCaptor.capture());
        assertEquals(7L, profileCaptor.getValue().getUserId());
        assertEquals("admin", profileCaptor.getValue().getUsername());
        assertEquals("管理员", profileCaptor.getValue().getName());
        assertEquals("https://demo-bucket.oss-cn-shenzhen.aliyuncs.com/avatar/admin.png", profileCaptor.getValue().getAvatar());
    }

    @Test
    void syncUserProfileShouldRejectMissingUserId() {
        InternalUserProfileSyncService service = new InternalUserProfileSyncService(userProfileMapper);

        BusinessException exception = assertThrows(BusinessException.class, () -> service.syncUserProfile(new InternalUserProfileSyncRequest()));

        assertEquals(ErrorCode.BAD_REQUEST, exception.getErrorCode());
        assertEquals("用户资料同步参数不完整", exception.getMessage());
    }
}
