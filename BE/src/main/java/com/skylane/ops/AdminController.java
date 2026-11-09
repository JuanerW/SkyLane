package com.skylane.ops;

import com.skylane.ops.OpsDtos.CreateOperator;
import com.skylane.ops.OpsDtos.CreateUser;
import com.skylane.ops.OpsDtos.OperatorRef;
import com.skylane.ops.OpsDtos.PatchUser;
import com.skylane.ops.OpsDtos.UserRef;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Design 8.1: /api/v1/admin/**, admin only (AppConfig). */
@RestController
@RequestMapping("/api/v1/admin")
@Profile("!memory")
public class AdminController {
    private final AdminService admin;

    public AdminController(AdminService admin) {
        this.admin = admin;
    }

    @GetMapping("/operators")
    public List<OperatorRef> operators() {
        return admin.operators();
    }

    @PostMapping("/operators")
    @ResponseStatus(HttpStatus.CREATED)
    public OperatorRef createOperator(@Valid @RequestBody CreateOperator req) {
        return admin.createOperator(req);
    }

    @GetMapping("/users")
    public List<UserRef> users() {
        return admin.users();
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public UserRef createUser(@Valid @RequestBody CreateUser req) {
        return admin.createUser(req);
    }

    @PatchMapping("/users/{id}")
    public UserRef patchUser(@PathVariable UUID id, @RequestBody PatchUser req) {
        return admin.patchUser(OpsController.actor().userId(), id, req);
    }
}
