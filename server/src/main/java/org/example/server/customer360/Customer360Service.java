package org.example.server.customer360;

import org.example.server.audit.AuditService;
import org.example.server.persistence.JpaNativeRepository;
import org.example.server.security.CurrentUser;
import org.example.server.util.BusinessClock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

@Service
public class Customer360Service {
    private final JpaNativeRepository jdbc;
    private final AuditService audit;
    public Customer360Service(JpaNativeRepository jdbc, AuditService audit){this.jdbc=jdbc;this.audit=audit;}

    @Transactional(readOnly=true)
    public Customer360Dtos.Summary summary(int customerId){
        requireCustomer(customerId); CurrentUser.requirePermission("CUSTOMERS.VIEW","View Customer 360");
        var customer=customer(customerId);
        BigDecimal outstanding=has("SALES.VIEW")?money("SELECT COALESCE(SUM(GREATEST(COALESCE(total_amount,0)-COALESCE(paid_amount,0),0)),0) FROM sales_header WHERE customer_id=? AND UPPER(COALESCE(document_status,'')) NOT IN ('DELETED','CANCELLED','REJECTED','PENDING APPROVAL')",customerId):BigDecimal.ZERO;
        BigDecimal quoteValue=BigDecimal.ZERO; long quoteCount=0;
        List<Customer360Dtos.QuotationRow> recentQuotes=List.of();
        if(has("QUOTATION.VIEW")){
            quoteValue=money("SELECT COALESCE(SUM(total_amount),0) FROM quotation_header WHERE customer_id=? AND UPPER(COALESCE(status,'')) NOT IN ('EXPIRED','REJECTED','DELETED','CONVERTED')",customerId);
            quoteCount=count("SELECT COUNT(*) FROM quotation_header WHERE customer_id=? AND UPPER(COALESCE(status,'')) NOT IN ('EXPIRED','REJECTED','DELETED','CONVERTED')",customerId);
            recentQuotes=quotations(customerId,5);
        }
        BigDecimal totalSales=has("SALES.VIEW")?money("SELECT COALESCE(SUM(total_amount),0) FROM sales_header WHERE customer_id=? AND UPPER(COALESCE(document_status,'')) NOT IN ('DELETED','CANCELLED','REJECTED','PENDING APPROVAL')",customerId):BigDecimal.ZERO;
        BigDecimal lastPayment=BigDecimal.ZERO; String lastPaymentDate="";
        if(has("SALES.VIEW")){
            var p=jdbc.query("SELECT COALESCE(pr.amount,0),COALESCE(pr.payment_date::text,'') FROM payment_record pr JOIN sales_header sh ON sh.id=pr.document_id AND pr.document_type='SALE' WHERE sh.customer_id=? ORDER BY dse_safe_date(pr.payment_date) DESC,pr.id DESC LIMIT 1",(r,i)->new Object[]{r.getBigDecimal(1),r.getString(2)},customerId);
            if(!p.isEmpty()){lastPayment=p.getFirst()[0] instanceof BigDecimal b?b:BigDecimal.ZERO;lastPaymentDate=Objects.toString(p.getFirst()[1],"");}
        }
        List<Customer360Dtos.InvoiceRow> recentInvoices=has("SALES.VIEW")?invoices(customerId,5):List.of();
        return new Customer360Dtos.Summary(customer,outstanding,quoteValue,quoteCount,totalSales,lastPayment,lastPaymentDate,recentQuotes,recentInvoices);
    }

    @Transactional(readOnly=true) public List<Customer360Dtos.QuotationRow> quotations(int customerId){CurrentUser.requirePermission("CUSTOMERS.VIEW","View Customer 360");CurrentUser.requirePermission("QUOTATION.VIEW","View customer quotations");requireCustomer(customerId);return quotations(customerId,500);}
    private List<Customer360Dtos.QuotationRow> quotations(int id,int limit){return jdbc.query("SELECT id,COALESCE(quotation_no,''),COALESCE(quotation_date::text,''),COALESCE(valid_until::text,''),COALESCE(salesperson,''),COALESCE(total_amount,0),COALESCE(status,''),COALESCE(follow_up_date::text,'') FROM quotation_header WHERE customer_id=? ORDER BY dse_safe_date(quotation_date) DESC,id DESC LIMIT "+limit,(r,i)->new Customer360Dtos.QuotationRow(r.getInt(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getBigDecimal(6),r.getString(7),r.getString(8)),id);}

    @Transactional(readOnly=true) public List<Customer360Dtos.InvoiceRow> invoices(int customerId){CurrentUser.requirePermission("CUSTOMERS.VIEW","View Customer 360");CurrentUser.requirePermission("SALES.VIEW","View customer invoices");requireCustomer(customerId);return invoices(customerId,500);}
    private List<Customer360Dtos.InvoiceRow> invoices(int customerId,int limit){return jdbc.query("SELECT id,invoice_no,COALESCE(invoice_date::text,''),COALESCE(total_amount,0),COALESCE(paid_amount,0),GREATEST(COALESCE(total_amount,0)-COALESCE(paid_amount,0),0),COALESCE(payment_status,''),COALESCE(document_status,'') FROM sales_header WHERE customer_id=? AND UPPER(COALESCE(document_status,''))<>'DELETED' ORDER BY dse_safe_date(invoice_date) DESC,id DESC LIMIT "+limit,(r,i)->new Customer360Dtos.InvoiceRow(r.getInt(1),r.getString(2),r.getString(3),r.getBigDecimal(4),r.getBigDecimal(5),r.getBigDecimal(6),r.getString(7),r.getString(8)),customerId);}

    @Transactional(readOnly=true) public List<Customer360Dtos.PaymentRow> payments(int customerId){CurrentUser.requirePermission("CUSTOMERS.VIEW","View Customer 360");CurrentUser.requirePermission("SALES.VIEW","View customer payments");requireCustomer(customerId);return jdbc.query("SELECT pr.id,COALESCE(pr.payment_date::text,''),COALESCE(pr.reference_no,''),COALESCE(pr.payment_mode,''),COALESCE(pr.amount,0),COALESCE(sh.invoice_no,''),COALESCE(pr.notes,'') FROM payment_record pr JOIN sales_header sh ON sh.id=pr.document_id AND pr.document_type='SALE' WHERE sh.customer_id=? ORDER BY dse_safe_date(pr.payment_date) DESC,pr.id DESC",(r,i)->new Customer360Dtos.PaymentRow(r.getInt(1),r.getString(2),r.getString(3),r.getString(4),r.getBigDecimal(5),r.getString(6),r.getString(7)),customerId);}

    @Transactional(readOnly=true) public List<Customer360Dtos.ContactRow> contacts(int customerId){CurrentUser.requirePermission("CUSTOMERS.VIEW","View customer contacts");requireCustomer(customerId);return jdbc.query("SELECT id,party_id,contact_name,COALESCE(designation,''),COALESCE(department,''),COALESCE(mobile,''),COALESCE(email,''),is_primary,COALESCE(is_email_recipient,false),COALESCE(is_whatsapp_recipient,false),COALESCE(notes,''),row_version,COALESCE(created_by,''),COALESCE(created_at::text,''),COALESCE(updated_by,''),COALESCE(updated_at::text,'') FROM party_contact WHERE party_id=? ORDER BY is_primary DESC,contact_name,id",(r,i)->new Customer360Dtos.ContactRow(r.getLong(1),r.getInt(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getString(7),r.getBoolean(8),r.getBoolean(9),r.getBoolean(10),r.getString(11),r.getLong(12),r.getString(13),r.getString(14),r.getString(15),r.getString(16)),customerId);}
    @Transactional public Customer360Dtos.ContactRow saveContact(int customerId,Customer360Dtos.ContactSave d){
        CurrentUser.requirePermission("CUSTOMERS.EDIT","Manage customer contacts");requireCustomer(customerId);
        if(d==null||blank(d.name()))throw new IllegalArgumentException("Contact name is required");
        Map<String,Object> before=contactAuditSnapshot(customerId);
        if(d.primary()){
            if(d.id()==null)jdbc.update("UPDATE party_contact SET is_primary=FALSE,row_version=row_version+1,updated_by=?,updated_at=? WHERE party_id=? AND is_primary=TRUE",user(),BusinessClock.nowUtcText(),customerId);
            else jdbc.update("UPDATE party_contact SET is_primary=FALSE,row_version=row_version+1,updated_by=?,updated_at=? WHERE party_id=? AND is_primary=TRUE AND id<>?",user(),BusinessClock.nowUtcText(),customerId,d.id());
        }
        long id;String action;
        if(d.id()==null){
            id=jdbc.queryForObject("INSERT INTO party_contact(party_id,contact_name,designation,department,mobile,email,is_primary,is_email_recipient,is_whatsapp_recipient,notes,created_by,created_at,updated_by,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id",Long.class,customerId,trim(d.name()),trim(d.designation()),trim(d.department()),trim(d.mobile()),trim(d.email()),d.primary(),d.emailRecipient(),d.whatsappRecipient(),trim(d.notes()),user(),BusinessClock.nowUtcText(),user(),BusinessClock.nowUtcText());
            action="CONTACT_CREATED";
        }else{
            int n=jdbc.update("UPDATE party_contact SET contact_name=?,designation=?,department=?,mobile=?,email=?,is_primary=?,is_email_recipient=?,is_whatsapp_recipient=?,notes=?,row_version=row_version+1,updated_by=?,updated_at=? WHERE id=? AND party_id=? AND row_version=?",trim(d.name()),trim(d.designation()),trim(d.department()),trim(d.mobile()),trim(d.email()),d.primary(),d.emailRecipient(),d.whatsappRecipient(),trim(d.notes()),user(),BusinessClock.nowUtcText(),d.id(),customerId,d.rowVersion());
            if(n!=1)throw new org.example.server.web.ConcurrentEditException("Customer contact");id=d.id();action="CONTACT_UPDATED";
        }
        audit.logChanges("PARTY",customerId,action,"Customer contact • "+trim(d.name()),audit.diff(before,contactAuditSnapshot(customerId)));
        long savedId=id;return contacts(customerId).stream().filter(x->x.id()==savedId).findFirst().orElseThrow();
    }
    @Transactional public void deleteContact(int customerId,long contactId,long rowVersion){
        CurrentUser.requirePermission("CUSTOMERS.EDIT","Delete customer contact");requireCustomer(customerId);
        Map<String,Object> before=contactAuditSnapshot(customerId);
        int n=jdbc.update("DELETE FROM party_contact WHERE id=? AND party_id=? AND row_version=?",contactId,customerId,rowVersion);
        if(n!=1)throw new org.example.server.web.ConcurrentEditException("Customer contact");
        audit.logChanges("PARTY",customerId,"CONTACT_DELETED","Customer contact #"+contactId,audit.diff(before,contactAuditSnapshot(customerId)));
    }

    @Transactional(readOnly=true) public List<Customer360Dtos.NoteRow> notes(int customerId){CurrentUser.requirePermission("CUSTOMERS.VIEW","View customer notes");requireCustomer(customerId);return jdbc.query("SELECT id,party_id,note_text,COALESCE(created_by,''),COALESCE(created_at::text,''),COALESCE(updated_by,''),COALESCE(updated_at::text,''),row_version FROM party_note WHERE party_id=? ORDER BY id DESC",(r,i)->new Customer360Dtos.NoteRow(r.getLong(1),r.getInt(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getString(7),r.getLong(8)),customerId);}
    @Transactional public Customer360Dtos.NoteRow saveNote(int customerId,Customer360Dtos.NoteSave d){
        CurrentUser.requirePermission("CUSTOMERS.EDIT","Manage customer notes");requireCustomer(customerId);
        if(d==null||blank(d.note()))throw new IllegalArgumentException("Note is required");
        Map<String,Object> before=noteAuditSnapshot(customerId);long id;String action;
        if(d.id()==null){
            id=jdbc.queryForObject("INSERT INTO party_note(party_id,note_text,created_by,created_at,updated_by,updated_at) VALUES(?,?,?,?,?,?) RETURNING id",Long.class,customerId,trim(d.note()),user(),BusinessClock.nowUtcText(),user(),BusinessClock.nowUtcText());action="NOTE_CREATED";
        }else{
            int n=jdbc.update("UPDATE party_note SET note_text=?,row_version=row_version+1,updated_by=?,updated_at=? WHERE id=? AND party_id=? AND row_version=?",trim(d.note()),user(),BusinessClock.nowUtcText(),d.id(),customerId,d.rowVersion());
            if(n!=1)throw new org.example.server.web.ConcurrentEditException("Customer note");id=d.id();action="NOTE_UPDATED";
        }
        audit.logChanges("PARTY",customerId,action,"Customer note #"+id,audit.diff(before,noteAuditSnapshot(customerId)));
        long savedId=id;return notes(customerId).stream().filter(x->x.id()==savedId).findFirst().orElseThrow();
    }
    @Transactional public void deleteNote(int customerId,long noteId,long rowVersion){
        CurrentUser.requirePermission("CUSTOMERS.EDIT","Delete customer note");requireCustomer(customerId);
        Map<String,Object> before=noteAuditSnapshot(customerId);
        int n=jdbc.update("DELETE FROM party_note WHERE id=? AND party_id=? AND row_version=?",noteId,customerId,rowVersion);
        if(n!=1)throw new org.example.server.web.ConcurrentEditException("Customer note");
        audit.logChanges("PARTY",customerId,"NOTE_DELETED","Customer note #"+noteId,audit.diff(before,noteAuditSnapshot(customerId)));
    }

    private Map<String,Object> contactAuditSnapshot(int partyId){
        Map<String,Object> out=new LinkedHashMap<>();
        jdbc.query("SELECT id,contact_name,COALESCE(designation,''),COALESCE(department,''),COALESCE(mobile,''),COALESCE(email,''),is_primary,COALESCE(is_email_recipient,false),COALESCE(is_whatsapp_recipient,false),COALESCE(notes,'') FROM party_contact WHERE party_id=? ORDER BY id",r->{
            long id=r.getLong(1);String p="Contact["+id+"] ";out.put(p+"Name",r.getString(2));out.put(p+"Designation",r.getString(3));out.put(p+"Department",r.getString(4));out.put(p+"Mobile",r.getString(5));out.put(p+"Email",r.getString(6));out.put(p+"Primary",r.getBoolean(7));out.put(p+"EmailAlert",r.getBoolean(8));out.put(p+"WhatsappAlert",r.getBoolean(9));out.put(p+"Notes",r.getString(10));},partyId);
        return out;
    }
    private Map<String,Object> noteAuditSnapshot(int partyId){
        Map<String,Object> out=new LinkedHashMap<>();
        jdbc.query("SELECT id,COALESCE(note_text,'') FROM party_note WHERE party_id=? ORDER BY id",r->{out.put("Note["+r.getLong(1)+"]",r.getString(2));},partyId);
        return out;
    }

    private Customer360Dtos.Customer customer(int id){var rows=jdbc.query("SELECT id,party_code,name,COALESCE(contact_person,''),COALESCE(phone,''),COALESCE(email,''),COALESCE(gstin,''),COALESCE(address,''),COALESCE(opening_balance,0),COALESCE(is_active,1),COALESCE(row_version,0) FROM party_master WHERE id=? AND UPPER(party_type)='CUSTOMER'",(r,i)->new Customer360Dtos.Customer(r.getInt(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getString(7),r.getString(8),r.getBigDecimal(9),r.getInt(10)!=0,r.getLong(11)),id);if(rows.isEmpty())throw new IllegalArgumentException("Customer not found");return rows.getFirst();}

    @Transactional(readOnly=true)
    public Customer360Dtos.CreditHealth creditHealth(int customerId){
        requireCustomerOrSalesView();
        var meta=jdbc.query("SELECT COALESCE(opening_balance,0),credit_limit FROM party_master WHERE id=? AND UPPER(party_type)='CUSTOMER'",
                (r,i)->new Object[]{r.getBigDecimal(1),r.getBigDecimal(2)},customerId);
        if(meta.isEmpty())throw new IllegalArgumentException("Customer not found");
        BigDecimal opening=meta.getFirst()[0] instanceof BigDecimal b?b:BigDecimal.ZERO;
        BigDecimal partyLimit=meta.getFirst()[1] instanceof BigDecimal b?b:null;
        BigDecimal defaultLimit=defaultCompanyCreditLimit();
        BigDecimal effectiveLimit=partyLimit!=null&&partyLimit.compareTo(BigDecimal.ZERO)>0?partyLimit:defaultLimit;
        BigDecimal unpaid=money("SELECT COALESCE(SUM(GREATEST(COALESCE(total_amount,0)-COALESCE(paid_amount,0),0)),0) FROM sales_header WHERE customer_id=? AND UPPER(COALESCE(document_status,'')) NOT IN ('DELETED','CANCELLED','REJECTED','PENDING APPROVAL')",customerId);
        BigDecimal netReceivable=opening.add(unpaid);
        String today=BusinessClock.today().toString();
        long overdueCount=count("SELECT COUNT(*) FROM sales_header WHERE customer_id=? AND UPPER(COALESCE(document_status,'')) NOT IN ('DELETED','CANCELLED','REJECTED','PENDING APPROVAL') AND GREATEST(COALESCE(total_amount,0)-COALESCE(paid_amount,0),0) > 0.01 AND COALESCE(TRIM(due_date),'')<>'' AND dse_safe_date(due_date) < dse_safe_date(?)",customerId,today);
        BigDecimal overdueAmount=money("SELECT COALESCE(SUM(GREATEST(COALESCE(total_amount,0)-COALESCE(paid_amount,0),0)),0) FROM sales_header WHERE customer_id=? AND UPPER(COALESCE(document_status,'')) NOT IN ('DELETED','CANCELLED','REJECTED','PENDING APPROVAL') AND GREATEST(COALESCE(total_amount,0)-COALESCE(paid_amount,0),0) > 0.01 AND COALESCE(TRIM(due_date),'')<>'' AND dse_safe_date(due_date) < dse_safe_date(?)",customerId,today);
        BigDecimal lastPay=BigDecimal.ZERO;String lastPayDate="";
        var p=jdbc.query("SELECT COALESCE(pr.amount,0),COALESCE(pr.payment_date::text,'') FROM payment_record pr JOIN sales_header sh ON sh.id=pr.document_id AND pr.document_type='SALE' WHERE sh.customer_id=? ORDER BY dse_safe_date(pr.payment_date) DESC,pr.id DESC LIMIT 1",(r,i)->new Object[]{r.getBigDecimal(1),r.getString(2)},customerId);
        if(!p.isEmpty()){lastPay=p.getFirst()[0] instanceof BigDecimal b?b:BigDecimal.ZERO;lastPayDate=Objects.toString(p.getFirst()[1],"");}
        double pct=effectiveLimit.compareTo(BigDecimal.ZERO)>0?netReceivable.doubleValue()*100d/effectiveLimit.doubleValue():0d;
        return new Customer360Dtos.CreditHealth(customerId,opening,unpaid,netReceivable,overdueCount,overdueAmount,effectiveLimit,pct,lastPay,lastPayDate);
    }

    @Transactional(readOnly=true)
    public Customer360Dtos.ItemPricingInsight itemPricing(int customerId,String itemCode,double qty){
        requireCustomerOrSalesView();
        requireCustomer(customerId);
        String code=trim(itemCode);
        if(code.isBlank())throw new IllegalArgumentException("Item code is required");
        double purchaseCost=0d,sellingPrice=0d;
        var itemRows=jdbc.query("SELECT COALESCE(purchase_price,0),COALESCE(selling_price,0) FROM item_master WHERE UPPER(item_code)=UPPER(?)",(r,i)->new double[]{r.getDouble(1),r.getDouble(2)},code);
        if(!itemRows.isEmpty()){purchaseCost=itemRows.getFirst()[0];sellingPrice=itemRows.getFirst()[1];}
        Double lastRate=null,lastDisc=null;String lastDate="",lastInv="";
        var hist=jdbc.query("SELECT sl.rate,COALESCE(sl.discount_percent,0),COALESCE(sh.invoice_date::text,''),COALESCE(sh.invoice_no,'') FROM sales_line sl JOIN sales_header sh ON sh.id=sl.sales_id WHERE sh.customer_id=? AND UPPER(sl.item_code)=UPPER(?) AND UPPER(COALESCE(sh.document_status,'')) NOT IN ('DELETED','CANCELLED','REJECTED') ORDER BY dse_safe_date(sh.invoice_date) DESC,sl.id DESC LIMIT 1",(r,i)->new Object[]{r.getObject(1),r.getObject(2),r.getString(3),r.getString(4)},customerId,code);
        if(!hist.isEmpty()){
            lastRate=hist.getFirst()[0] instanceof Number n?n.doubleValue():null;
            lastDisc=hist.getFirst()[1] instanceof Number n?n.doubleValue():0d;
            lastDate=Objects.toString(hist.getFirst()[2],"");
            lastInv=Objects.toString(hist.getFirst()[3],"");
        }
        String today=BusinessClock.today().toString();
        double effectiveQty=qty>0?qty:1d;
        List<Customer360Dtos.PriceRuleRow> slabs=priceRules(customerId).stream()
                .filter(r->r.itemCode().equalsIgnoreCase(code)&&r.active())
                .sorted(Comparator.comparingDouble(Customer360Dtos.PriceRuleRow::minQty)
                        .thenComparingLong(Customer360Dtos.PriceRuleRow::id).reversed())
                .toList();
        Customer360Dtos.PriceRuleRow matched=slabs.stream()
                .filter(r->r.minQty()<=effectiveQty+0.0001)
                .filter(r->(blank(r.validFrom())||r.validFrom().compareTo(today)<=0)&&(blank(r.validTo())||r.validTo().compareTo(today)>=0))
                .findFirst().orElse(null);
        List<Customer360Dtos.PriceRuleRow> ascSlabs=slabs.stream()
                .sorted(Comparator.comparingDouble(Customer360Dtos.PriceRuleRow::minQty))
                .toList();
        return new Customer360Dtos.ItemPricingInsight(customerId,code,lastRate,lastDisc,lastDate,lastInv,purchaseCost,sellingPrice,matched,ascSlabs);
    }

    @Transactional(readOnly=true)
    public List<Customer360Dtos.PriceRuleRow> priceRules(int customerId){
        requireCustomerOrSalesView();
        requireCustomer(customerId);
        return jdbc.query("SELECT r.id,r.party_id,r.item_code,COALESCE(im.description,r.item_code),r.min_qty,r.special_rate,r.discount_percent,COALESCE(r.valid_from,''),COALESCE(r.valid_to,''),COALESCE(r.notes,''),r.active,r.row_version FROM customer_price_rule r LEFT JOIN item_master im ON UPPER(im.item_code)=UPPER(r.item_code) WHERE r.party_id=? ORDER BY UPPER(r.item_code),r.min_qty,r.id",
                (r,i)->new Customer360Dtos.PriceRuleRow(r.getLong(1),r.getInt(2),r.getString(3),r.getString(4),r.getDouble(5),r.getDouble(6),r.getDouble(7),r.getString(8),r.getString(9),r.getString(10),r.getBoolean(11),r.getLong(12)),customerId);
    }

    @Transactional
    public Customer360Dtos.PriceRuleRow savePriceRule(int customerId,Customer360Dtos.PriceRuleSave d){
        requireCustomerOrSalesEdit();
        requireCustomer(customerId);
        if(d==null||blank(d.itemCode()))throw new IllegalArgumentException("Item code is required");
        if(d.minQty()<=0)throw new IllegalArgumentException("Minimum quantity must be greater than zero");
        if(d.specialRate()<0)throw new IllegalArgumentException("Special rate cannot be negative");
        if(d.discountPercent()<0||d.discountPercent()>100)throw new IllegalArgumentException("Discount % must be between 0 and 100");
        String code=trim(d.itemCode());
        long id;String action;
        if(d.id()==null){
            id=jdbc.queryForObject("INSERT INTO customer_price_rule(party_id,item_code,min_qty,special_rate,discount_percent,valid_from,valid_to,notes,active,created_by,created_at,updated_by,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id",
                    Long.class,customerId,code,d.minQty(),d.specialRate(),d.discountPercent(),trim(d.validFrom()),trim(d.validTo()),trim(d.notes()),d.active(),user(),BusinessClock.nowUtcText(),user(),BusinessClock.nowUtcText());
            action="PRICE_RULE_CREATED";
        }else{
            int n=jdbc.update("UPDATE customer_price_rule SET item_code=?,min_qty=?,special_rate=?,discount_percent=?,valid_from=?,valid_to=?,notes=?,active=?,row_version=row_version+1,updated_by=?,updated_at=? WHERE id=? AND party_id=? AND row_version=?",
                    code,d.minQty(),d.specialRate(),d.discountPercent(),trim(d.validFrom()),trim(d.validTo()),trim(d.notes()),d.active(),user(),BusinessClock.nowUtcText(),d.id(),customerId,d.rowVersion());
            if(n!=1)throw new org.example.server.web.ConcurrentEditException("Customer price rule");
            id=d.id();action="PRICE_RULE_UPDATED";
        }
        audit.log("PARTY",customerId,action,"Price rule for "+code+" (qty >= "+d.minQty()+") @ "+d.specialRate());
        long savedId=id;
        return priceRules(customerId).stream().filter(x->x.id()==savedId).findFirst().orElseThrow();
    }

    @Transactional
    public void deletePriceRule(int customerId,long ruleId,long rowVersion){
        requireCustomerOrSalesEdit();
        requireCustomer(customerId);
        int n=jdbc.update("DELETE FROM customer_price_rule WHERE id=? AND party_id=? AND row_version=?",ruleId,customerId,rowVersion);
        if(n!=1)throw new org.example.server.web.ConcurrentEditException("Customer price rule");
        audit.log("PARTY",customerId,"PRICE_RULE_DELETED","Customer price rule #"+ruleId);
    }

    private BigDecimal defaultCompanyCreditLimit(){
        try{
            var rows=jdbc.query("SELECT setting_value FROM application_setting WHERE setting_key='credit_control.default_limit'",(r,i)->r.getString(1));
            if(!rows.isEmpty()&&!blank(rows.getFirst())){
                BigDecimal v=new BigDecimal(rows.getFirst().trim());
                if(v.compareTo(BigDecimal.ZERO)>=0)return v;
            }
        }catch(Exception ignored){}
        return new BigDecimal("500000.00");
    }
    private void requireCustomerOrSalesView(){
        if(!(has("CUSTOMERS.VIEW")||has("SALES.VIEW")||has("SALES.CREATE")||has("SALES.EDIT"))){
            CurrentUser.requirePermission("CUSTOMERS.VIEW","View Customer 360");
        }
    }
    private void requireCustomerOrSalesEdit(){
        if(!(has("CUSTOMERS.EDIT")||has("ITEMS.EDIT")||has("SALES.EDIT"))){
            CurrentUser.requirePermission("CUSTOMERS.EDIT","Manage customer price lists");
        }
    }
    private void requireCustomer(int id){customer(id);}
    private boolean has(String p){return CurrentUser.hasPermission(p);}
    private String user(){return CurrentUser.require().username();}
    private static boolean blank(String v){return v==null||v.trim().isBlank();}
    private static String trim(String v){return v==null?"":v.trim();}
    private long count(String sql,Object...a){Long n=jdbc.queryForObject(sql,Long.class,a);return n==null?0:n;}
    private BigDecimal money(String sql,Object...a){BigDecimal n=jdbc.queryForObject(sql,BigDecimal.class,a);return n==null?BigDecimal.ZERO:n;}
}
