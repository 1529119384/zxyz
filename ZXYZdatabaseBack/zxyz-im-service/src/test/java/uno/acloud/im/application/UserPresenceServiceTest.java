package uno.acloud.im.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.acloud.im.convert.ImEntityMapper;
import uno.acloud.im.infrastructure.mapper.TeamMapper;
import uno.acloud.im.infrastructure.persistence.entity.UserPresence;
import uno.acloud.im.infrastructure.mapper.UserPresenceMapper;
import uno.acloud.im.vo.UserPresenceVO;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 60 号 🟡-1 防回归：presence 批量查询必须过滤「无同团队关系」的目标 ID——
 * 无关系目标回退为离线占位（online=false、未暴露真实活跃时间），不泄露在线存在性。
 */
@ExtendWith(MockitoExtension.class)
class UserPresenceServiceTest {

    private static final Long REQUESTER_ID = 7L;
    private static final Long TEAMMATE_ID = 8L;
    private static final Long STRANGER_ID = 9L;

    @Mock
    private UserPresenceMapper userPresenceMapper;
    @Mock
    private TeamMapper teamMapper;
    @Mock
    private ImEntityMapper imEntityMapper;

    private UserPresenceService service;

    @BeforeEach
    void setUp() {
        service = new UserPresenceService(userPresenceMapper, teamMapper, imEntityMapper);
    }

    @Test
    void listPresenceShouldReturnRealPresenceOnlyForRelatedUsers() {
        UserPresence teammatePresence = new UserPresence();
        teammatePresence.setUserId(TEAMMATE_ID);
        when(userPresenceMapper.listByUserIds(List.of(TEAMMATE_ID, STRANGER_ID)))
                .thenReturn(List.of(teammatePresence));
        when(teamMapper.listSharedActiveTeamIds(REQUESTER_ID, TEAMMATE_ID)).thenReturn(List.of(6L));
        when(teamMapper.listSharedActiveTeamIds(REQUESTER_ID, STRANGER_ID)).thenReturn(List.of());
        when(imEntityMapper.toPresenceVO(teammatePresence))
                .thenReturn(new UserPresenceVO(TEAMMATE_ID, true, 1, null));

        List<UserPresenceVO> result = service.listPresence(REQUESTER_ID, List.of(TEAMMATE_ID, STRANGER_ID));

        assertEquals(2, result.size());
        UserPresenceVO teammate = result.get(0);
        UserPresenceVO stranger = result.get(1);
        assertEquals(TEAMMATE_ID, teammate.getUserId());
        assertTrue(teammate.getOnline());
        assertEquals(STRANGER_ID, stranger.getUserId());
        assertFalse(stranger.getOnline());
    }

    @Test
    void listPresenceShouldReturnOwnPresenceWithoutTeamLookup() {
        UserPresence ownPresence = new UserPresence();
        ownPresence.setUserId(REQUESTER_ID);
        when(userPresenceMapper.listByUserIds(List.of(REQUESTER_ID))).thenReturn(List.of(ownPresence));
        when(imEntityMapper.toPresenceVO(ownPresence))
                .thenReturn(new UserPresenceVO(REQUESTER_ID, true, 2, null));

        List<UserPresenceVO> result = service.listPresence(REQUESTER_ID, List.of(REQUESTER_ID));

        assertEquals(1, result.size());
        assertTrue(result.get(0).getOnline());
        // 自己的在线状态查询不应触发共享团队 SQL。
        org.mockito.Mockito.verify(teamMapper, org.mockito.Mockito.never())
                .listSharedActiveTeamIds(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void listPresenceShouldDeduplicateAndIgnoreInvalidIds() {
        when(userPresenceMapper.listByUserIds(List.of(TEAMMATE_ID))).thenReturn(List.of());
        when(teamMapper.listSharedActiveTeamIds(REQUESTER_ID, TEAMMATE_ID)).thenReturn(List.of(6L));

        List<UserPresenceVO> result = service.listPresence(REQUESTER_ID,
                java.util.Arrays.asList(TEAMMATE_ID, TEAMMATE_ID, 0L, -1L, null));

        assertEquals(1, result.size());
        assertEquals(TEAMMATE_ID, result.get(0).getUserId());
        assertFalse(result.get(0).getOnline());
    }
}
