package org.example.util;

import org.example.api.customer360.Customer360ApiClient;
import org.example.api.supplier360.Supplier360ApiClient;
import org.example.model.Party;

import java.util.*;

/**
 * Enterprise Communication Recipient Resolver for Jasvi ERP Desktop.
 * Dynamically aggregates multiple recipient email addresses and WhatsApp phone numbers
 * from Customer 360° and Supplier 360° contact books based on user-configured
 * notification preferences (is_email_recipient, is_whatsapp_recipient).
 */
public final class PartyRecipientResolver {

    private PartyRecipientResolver() {}

    public record ContactOption(
            long id,
            String name,
            String designation,
            String email,
            String phone,
            boolean emailRecipient,
            boolean whatsappRecipient
    ) {
        public String displayLabel() {
            String role = (designation == null || designation.isBlank()) ? "" : " (" + designation.trim() + ")";
            return name + role;
        }
    }

    public record ResolvedRecipients(
            String emailString,
            String phoneString,
            List<String> emails,
            List<String> phones,
            List<ContactOption> availableContacts
    ) {}

    public static ResolvedRecipients resolveCustomer(Party customer) {
        if (customer == null) {
            return new ResolvedRecipients("", "", List.of(), List.of(), List.of());
        }
        return resolveCustomer(customer.getId(), customer.getEmail(), customer.getPhone());
    }

    public static ResolvedRecipients resolveCustomer(int customerId, String fallbackEmail, String fallbackPhone) {
        List<ContactOption> options = new ArrayList<>();
        Set<String> emails = new LinkedHashSet<>();
        Set<String> phones = new LinkedHashSet<>();

        String cleanMasterEmail = sanitizeEmail(fallbackEmail);
        String cleanMasterPhone = sanitizePhone(fallbackPhone);

        if (customerId > 0) {
            try {
                Customer360ApiClient api = new Customer360ApiClient();
                List<Customer360ApiClient.ContactRow> contacts = api.contacts(customerId);
                if (contacts != null) {
                    for (var c : contacts) {
                        String cEmail = sanitizeEmail(c.email());
                        String cPhone = sanitizePhone(c.mobile());
                        options.add(new ContactOption(c.id(), c.name(), c.designation(), cEmail, cPhone, c.emailRecipient(), c.whatsappRecipient()));

                        if (c.emailRecipient() && !cEmail.isBlank()) {
                            emails.add(cEmail);
                        }
                        if (c.whatsappRecipient() && !cPhone.isBlank()) {
                            phones.add(cPhone);
                        }
                    }
                }
            } catch (Exception ex) {
                System.err.println("[PartyRecipientResolver] Failed to fetch customer 360 contacts for #" + customerId + ": " + ex.getMessage());
            }
        }

        // Include master party email / phone if present or if no contacts were explicitly ticked
        if (!cleanMasterEmail.isBlank()) {
            emails.add(cleanMasterEmail);
        }
        if (!cleanMasterPhone.isBlank()) {
            phones.add(cleanMasterPhone);
        }

        String emailStr = String.join(", ", emails);
        String phoneStr = String.join(", ", phones);

        return new ResolvedRecipients(emailStr, phoneStr, new ArrayList<>(emails), new ArrayList<>(phones), options);
    }

    public static ResolvedRecipients resolveSupplier(Party supplier) {
        if (supplier == null) {
            return new ResolvedRecipients("", "", List.of(), List.of(), List.of());
        }
        return resolveSupplier(supplier.getId(), supplier.getEmail(), supplier.getPhone());
    }

    public static ResolvedRecipients resolveSupplier(int supplierId, String fallbackEmail, String fallbackPhone) {
        List<ContactOption> options = new ArrayList<>();
        Set<String> emails = new LinkedHashSet<>();
        Set<String> phones = new LinkedHashSet<>();

        String cleanMasterEmail = sanitizeEmail(fallbackEmail);
        String cleanMasterPhone = sanitizePhone(fallbackPhone);

        if (supplierId > 0) {
            try {
                Supplier360ApiClient api = new Supplier360ApiClient();
                List<Supplier360ApiClient.ContactRow> contacts = api.contacts(supplierId);
                if (contacts != null) {
                    for (var c : contacts) {
                        String cEmail = sanitizeEmail(c.email());
                        String cPhone = sanitizePhone(c.mobile());
                        options.add(new ContactOption(c.id(), c.name(), c.designation(), cEmail, cPhone, c.emailRecipient(), c.whatsappRecipient()));

                        if (c.emailRecipient() && !cEmail.isBlank()) {
                            emails.add(cEmail);
                        }
                        if (c.whatsappRecipient() && !cPhone.isBlank()) {
                            phones.add(cPhone);
                        }
                    }
                }
            } catch (Exception ex) {
                System.err.println("[PartyRecipientResolver] Failed to fetch supplier 360 contacts for #" + supplierId + ": " + ex.getMessage());
            }
        }

        if (!cleanMasterEmail.isBlank()) {
            emails.add(cleanMasterEmail);
        }
        if (!cleanMasterPhone.isBlank()) {
            phones.add(cleanMasterPhone);
        }

        String emailStr = String.join(", ", emails);
        String phoneStr = String.join(", ", phones);

        return new ResolvedRecipients(emailStr, phoneStr, new ArrayList<>(emails), new ArrayList<>(phones), options);
    }

    public static ResolvedRecipients resolveParty(String partyType, int partyId, String fallbackEmail, String fallbackPhone) {
        if ("SUPPLIER".equalsIgnoreCase(partyType)) {
            return resolveSupplier(partyId, fallbackEmail, fallbackPhone);
        }
        return resolveCustomer(partyId, fallbackEmail, fallbackPhone);
    }

    private static String sanitizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private static String sanitizePhone(String phone) {
        if (phone == null) return "";
        String trimmed = phone.trim();
        return trimmed.replaceAll("\\s+", "");
    }
}
