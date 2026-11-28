package org.example.server.profile;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/profile")
public class ProfileController {
    private final ProfileService service;

    public ProfileController(ProfileService service) {
        this.service = service;
    }

    @GetMapping
    public ProfileDtos.ProfileDto current() {
        return service.current();
    }

    @PutMapping
    public ProfileDtos.ProfileDto update(@RequestBody ProfileDtos.ProfileUpdate r) {
        return service.update(r);
    }

    @PutMapping("/avatar")
    public ProfileDtos.ProfileDto updateAvatar(@RequestBody ProfileDtos.AvatarUpdateRequest r) {
        return service.updateAvatar(r == null ? "" : r.avatarData());
    }

    @DeleteMapping("/avatar")
    public ProfileDtos.ProfileDto removeAvatar() {
        return service.updateAvatar("");
    }
}
