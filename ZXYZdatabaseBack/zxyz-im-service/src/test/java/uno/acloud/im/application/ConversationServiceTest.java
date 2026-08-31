package uno.acloud.im.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.acloud.common.ErrorCode;
import static uno.acloud.common.TeamErrorCode.*;
import uno.acloud.exception.BusinessException;
import uno.acloud.im.infrastructure.persistence.entity.ImConversation;
import uno.acloud.im.infrastructure.persistence.entity.TeamMember;
import uno.acloud.im.infrastructure.mapper.ConversationMapper;
import uno.acloud.im.infrastructure.mapper.TeamMapper;
import uno.acloud.im.vo.ConversationSummaryVO;
import uno.acloud.im.vo.TeamConversationVO;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConversationServiceTest {

    @Mock
    private ConversationMapper conversationMapper;
    @Mock
    private TeamMapper teamMapper;

    private ConversationService service;

    @BeforeEach
    void setUp() {
        service = new ConversationService(conversationMapper, teamMapper);
    }

    // ---- getTeamConversation tests ----

    @Test
    void getTeamConversation_shouldReturnConversationWhenUserIsMember() {
        TeamConversationVO vo = new TeamConversationVO(10L, 5L, "My Team", null, "TEAM");
        when(conversationMapper.getTeamConversation(5L, 100L)).thenReturn(vo);

        TeamConversationVO result = service.getTeamConversation(100L, 5L);

        assertNotNull(result);
        assertEquals(10L, result.getConversationId());
        assertEquals(5L, result.getTeamId());
        assertEquals("My Team", result.getTeamName());
    }

    @Test
    void getTeamConversation_shouldThrowWhenNotMember() {
        when(conversationMapper.getTeamConversation(5L, 100L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.getTeamConversation(100L, 5L));

        assertEquals(TEAM_NOT_FOUND.getCode(), ex.getErrorCode());
    }

    // ---- requireConversationMember tests ----

    @Test
    void requireConversationMember_shouldPassWhenUserIsActiveMember() {
        ImConversation conversation = new ImConversation();
        conversation.setId(10L);
        conversation.setTeamId(5L);
        when(conversationMapper.getConversationWithActiveMember(10L, 100L)).thenReturn(conversation);

        TeamMember member = new TeamMember();
        member.setTeamId(5L);
        member.setUserId(100L);
        when(teamMapper.getActiveMember(5L, 100L)).thenReturn(member);

        assertDoesNotThrow(() -> service.requireConversationMember(10L, 100L));
    }

    @Test
    void requireConversationMember_shouldThrowWhenNotMember() {
        when(conversationMapper.getConversationWithActiveMember(10L, 100L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.requireConversationMember(10L, 100L));

        assertEquals(ErrorCode.NO_PERMISSION, ex.getErrorCode());
    }

    @Test
    void requireConversationMember_shouldThrowWhenConversationIdIsNull() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.requireConversationMember(null, 100L));

        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    @Test
    void requireConversationMember_shouldThrowWhenConversationIdIsZero() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.requireConversationMember(0L, 100L));

        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    @Test
    void requireConversationMember_shouldThrowWhenTeamMemberInactive() {
        ImConversation conversation = new ImConversation();
        conversation.setId(10L);
        conversation.setTeamId(5L);
        when(conversationMapper.getConversationWithActiveMember(10L, 100L)).thenReturn(conversation);
        when(teamMapper.getActiveMember(5L, 100L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.requireConversationMember(10L, 100L));

        assertEquals(ErrorCode.NO_PERMISSION, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("不在该团队中"));
    }

    @Test
    void requireConversationMember_shouldSkipTeamCheck_whenNoTeamId() {
        // Direct conversations have no teamId
        ImConversation conversation = new ImConversation();
        conversation.setId(10L);
        conversation.setTeamId(null);
        when(conversationMapper.getConversationWithActiveMember(10L, 100L)).thenReturn(conversation);

        assertDoesNotThrow(() -> service.requireConversationMember(10L, 100L));
        verifyNoInteractions(teamMapper);
    }

    // ---- getConversationSummary tests ----

    @Test
    void getConversationSummary_shouldReturnSummaryWhenAuthorized() {
        ImConversation conversation = new ImConversation();
        conversation.setId(10L);
        conversation.setTeamId(5L);
        when(conversationMapper.getConversationWithActiveMember(10L, 100L)).thenReturn(conversation);
        when(teamMapper.getActiveMember(5L, 100L)).thenReturn(new TeamMember());

        ConversationSummaryVO summary = new ConversationSummaryVO(
                10L, "TEAM", 5L, null, "My Team", null, 0,
                null, null, null, null, LocalDateTime.now());
        when(conversationMapper.getConversationSummary(10L, 100L)).thenReturn(summary);

        ConversationSummaryVO result = service.getConversationSummary(100L, 10L);

        assertNotNull(result);
        assertEquals(10L, result.getId());
        assertEquals("My Team", result.getName());
    }

    @Test
    void getConversationSummary_shouldThrowWhenSummaryNotFound() {
        ImConversation conversation = new ImConversation();
        conversation.setId(10L);
        conversation.setTeamId(5L);
        when(conversationMapper.getConversationWithActiveMember(10L, 100L)).thenReturn(conversation);
        when(teamMapper.getActiveMember(5L, 100L)).thenReturn(new TeamMember());
        when(conversationMapper.getConversationSummary(10L, 100L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.getConversationSummary(100L, 10L));

        assertEquals(ErrorCode.NO_PERMISSION, ex.getErrorCode());
    }

    // ---- listMyConversations tests ----

    @Test
    void listMyConversations_shouldDelegateToMapper() {
        ConversationSummaryVO summary = new ConversationSummaryVO(
                10L, "TEAM", 5L, null, "My Team", null, 3,
                null, null, null, null, LocalDateTime.now());
        when(conversationMapper.listMyConversations(100L, 5L)).thenReturn(List.of(summary));

        List<ConversationSummaryVO> result = service.listMyConversations(100L, 5L);

        assertEquals(1, result.size());
        assertEquals(10L, result.get(0).getId());
    }

    // ---- requireWritableConversation tests ----

    @Test
    void requireWritableConversation_shouldPassWhenNotReadOnly() {
        ImConversation conversation = new ImConversation();
        conversation.setId(10L);
        conversation.setReadOnly(Boolean.FALSE);
        when(conversationMapper.getConversationById(10L)).thenReturn(conversation);

        assertDoesNotThrow(() -> service.requireWritableConversation(10L));
    }

    @Test
    void requireWritableConversation_shouldThrowWhenReadOnly() {
        ImConversation conversation = new ImConversation();
        conversation.setId(10L);
        conversation.setReadOnly(Boolean.TRUE);
        when(conversationMapper.getConversationById(10L)).thenReturn(conversation);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.requireWritableConversation(10L));

        assertEquals(TEAM_PERMISSION_DENIED.getCode(), ex.getErrorCode());
        assertTrue(ex.getMessage().contains("归档"));
    }

    @Test
    void requireWritableConversation_shouldPassWhenConversationNotFound() {
        when(conversationMapper.getConversationById(99L)).thenReturn(null);

        // conversation 不存在时不抛异常（后续业务逻辑会处理）
        assertDoesNotThrow(() -> service.requireWritableConversation(99L));
    }

    @Test
    void requireWritableConversation_shouldPassWhenReadOnlyIsNull() {
        ImConversation conversation = new ImConversation();
        conversation.setId(10L);
        conversation.setReadOnly(null);
        when(conversationMapper.getConversationById(10L)).thenReturn(conversation);

        assertDoesNotThrow(() -> service.requireWritableConversation(10L));
    }
}
