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
            });
        }

        chain.doFilter(request, response);
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

        // The filter stores an Actor; expose its role and company as an AccessScope.
        if (auth != null && auth.getDetails() instanceof Actor a) {
            return new AccessScope(a.role(), a.operatorId());
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
