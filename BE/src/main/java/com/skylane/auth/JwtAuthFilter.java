package com.skylane.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/**
 * Verifies the bearer token and checks the user's token version.
 * For a valid login, stores the user ID, role, and operator ID
 * in the security context for the current request.
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwt;
    private final JdbcTemplate jdbc;

    // role 表示用户角色，operatorId 表示运营方员工所属的公司
    public record AccessScope(String role, UUID operatorId) {
    }

    // 保存从用户表查到的 token 版本和权限信息
    private record LoginRow(int tokenVersion, AccessScope scope) {
    }

    public JwtAuthFilter(JwtService jwt, JdbcTemplate jdbc) {
        this.jwt = jwt;
        this.jdbc = jdbc;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain
    ) throws ServletException, IOException {

        String header = request.getHeader("Authorization");

        if (header != null && header.startsWith("Bearer ")) {
            jwt.verify(header.substring(7).trim()).ifPresent(claims -> {
                // D28: role is read here on every request (not stored in the JWT), so role changes apply at once.
                List<Actor> rows = jdbc.query(
                        "SELECT token_version, role, operator_id FROM app_user WHERE id = ? AND disabled_at IS NULL",
                        (rs, i) -> new Actor(claims.userId(), rs.getString("role"), rs.getObject("operator_id", UUID.class),
                                rs.getInt("token_version")),
                        claims.userId());
                if (!rows.isEmpty() && rows.get(0).tokenVersion() == claims.tokenVersion()) {
                    Actor actor = rows.get(0);
                    var auth = new UsernamePasswordAuthenticationToken(claims.userId(), null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + actor.role().toUpperCase())));
                    auth.setDetails(actor);
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            }
        }

        chain.doFilter(request, response);
    }

    // JdbcTemplate 每读到一行查询结果，就用这个方法转成 LoginRow
    private LoginRow readLoginRow(ResultSet rs, int rowNum) throws SQLException {
        int tokenVersion = rs.getInt("token_version");
        String role = rs.getString("role");
        UUID operatorId = rs.getObject("operator_id", UUID.class);

        return new LoginRow(tokenVersion, new AccessScope(role, operatorId));
    }

    public static UUID currentUserIdOrNull() {
        var auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth != null && auth.getPrincipal() instanceof UUID userId) {
            return userId;
        }

        return null;
    }

    public static AccessScope currentAccessScopeOrNull() {
        var auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth != null && auth.getDetails() instanceof AccessScope scope) {
            return scope;
        }

        return null;
    }

    /** The signed-in user's role (customer / operator / admin) and company, design 8.1 (D27). */
    public record Actor(UUID userId, String role, UUID operatorId, int tokenVersion) {
        public boolean admin() {
            return "admin".equals(role);
        }
    }

    public static Actor currentActorOrNull() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getDetails() instanceof Actor a ? a : null;
    }
}
