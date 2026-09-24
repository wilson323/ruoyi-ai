package org.ruoyi.ipd.controller;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.security.IpdPermissionCode;
import org.ruoyi.ipd.security.IpdRolePermissionCatalog;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R211b：/auth/me PersonView 必须附带 IpdRolePermissionCatalog 权限码，供前端 accessCodes。
 */
@Tag("dev")
class IpdAuthMePermissionCodesTest {

    @Test
    void personView_from_rdPm_includesProjectListCode() {
        Person person = Person.builder()
            .id(1001L)
            .name("rd")
            .username("ipd-rd")
            .personType("RD_PM")
            .accountStatus("ACTIVE")
            .build();
        IpdAuthController.PersonView view = IpdAuthController.PersonView.from(person);
        assertThat(view.permissionCodes()).isNotEmpty();
        assertThat(view.permissionCodes()).contains(IpdPermissionCode.OPERATION_MODULE_PROJECT);
        assertThat(view.permissionCodes())
            .containsExactlyElementsOf(IpdRolePermissionCatalog.permissionsOf("RD_PM"));
    }

    @Test
    void personView_from_superAdmin_matchesCatalog() {
        Person person = Person.builder()
            .id(1L)
            .name("admin")
            .username("ipd-admin")
            .personType("SUPER_ADMIN")
            .accountStatus("ACTIVE")
            .build();
        List<String> codes = IpdAuthController.PersonView.from(person).permissionCodes();
        assertThat(codes).contains(IpdPermissionCode.OPERATION_MODULE_PROJECT);
        assertThat(codes).containsExactlyElementsOf(IpdRolePermissionCatalog.permissionsOf("SUPER_ADMIN"));
    }

    @Test
    void personView_from_unknownType_emptyCodes() {
        Person person = Person.builder()
            .id(2L)
            .name("x")
            .username("x")
            .personType("UNKNOWN")
            .accountStatus("ACTIVE")
            .build();
        assertThat(IpdAuthController.PersonView.from(person).permissionCodes()).isEmpty();
    }
}
