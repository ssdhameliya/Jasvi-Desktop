package org.example.server.auth;
public final class AuthDtos { private AuthDtos() {}
 public record LoginRequest(String identity,String password){}
 public record LoginMfaCompleteRequest(String challengeId,String otp){}
 public record LoginMfaResendRequest(String challengeId){}
 public record LoginMfaEnrollmentRequest(String challengeId){}
 public record LoginMfaRecoveryRequest(String challengeId){}
 public record LoginMfaRecoveryCompleteRequest(String loginChallengeId,String recoveryChallengeId,String otp){}
 public record LoginMfaEnrollmentResponse(boolean success,String challengeId,String manualSecret,String provisioningUri,String message){}
 public record UserIdRequest(int userId){}
 public record ChangePasswordRequest(int userId,String currentPassword,String password){}
 public record ChangePasswordResponse(boolean success,String message,String accessToken,String expiresAt){
     public ChangePasswordResponse(boolean success,String message) {
         this(success,message,null,null);
     }
 }
 public record RegisterRequest(String username,String password,String fullName,String email,String phone,String role,boolean mfaEnabled){}
 public record CaptchaResponse(String challengeId,String question,String expiresIn){}
 public record RegistrationOtpRequest(String username,String fullName,String email,String phone,String role,boolean mfaEnabled,String captchaChallengeId,String captchaAnswer){}
 public record RegistrationEmailVerifyRequest(String challengeId,String otp,String username,String password,String fullName,String email,String phone,String role,boolean mfaEnabled){}
 public record RegistrationMfaSetupResponse(boolean success,Long registrationId,String manualSecret,String provisioningUri,String message){}
 public record RegistrationMfaCompleteRequest(long registrationId,String otp){}
 public record PasswordResetOtpRequest(String identity, String captchaChallengeId, String captchaAnswer) {
     public PasswordResetOtpRequest(String identity) { this(identity, null, null); }
 }
 public record PasswordResetCompleteRequest(String challengeId,String otp,String totp,String password){}
 public record ChallengeResponse(boolean success,String challengeId,String message,boolean totpRequired){
     public ChallengeResponse(boolean success,String challengeId,String message) {
         this(success,challengeId,message,false);
     }
 }
 public record LoginMfaChallengeResponse(boolean success,String challengeId,String message,String maskedDestination){}
 public record LoginResponse(boolean success,UserPayload user,String message,String accessToken,String expiresAt,boolean mfaRequired,String challengeId,String maskedDestination,String incidentTicketId){
     public LoginResponse(boolean success,UserPayload user,String message,String accessToken,String expiresAt,boolean mfaRequired,String challengeId,String maskedDestination) {
         this(success,user,message,accessToken,expiresAt,mfaRequired,challengeId,maskedDestination,null);
     }
 }
 public record OperationResponse(boolean success,String message,String incidentTicketId){
     public OperationResponse(boolean success,String message) {
         this(success,message,null);
     }
 }
 public record RoleOption(String code,String displayName){}
 public record EffectivePermission(String module,String action,String description){}
 public record UserPayload(int id,String username,String fullName,String role,Integer roleId,String email,String phone,boolean active,String department,String branch,String accessLevel,boolean locked,boolean mfaEnabled,boolean forcePasswordChange,String avatarData){
     public UserPayload(int id,String username,String fullName,String role,Integer roleId,String email,String phone,boolean active,String department,String branch,String accessLevel,boolean locked,boolean mfaEnabled,boolean forcePasswordChange) {
         this(id,username,fullName,role,roleId,email,phone,active,department,branch,accessLevel,locked,mfaEnabled,forcePasswordChange,"");
     }
     public UserPayload(int id,String username,String fullName,String role,Integer roleId,String email,String phone,boolean active,String department,String branch,String accessLevel,boolean locked,boolean mfaEnabled) {
         this(id,username,fullName,role,roleId,email,phone,active,department,branch,accessLevel,locked,mfaEnabled,false,"");
     }
 }
 public record SessionExtendResponse(boolean success,String message,String accessToken,String expiresAt){}
}
