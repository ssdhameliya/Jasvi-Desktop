package org.example.controller;

import org.example.navigation.ScreenLifecycle;
import javafx.fxml.FXML;
import org.example.model.Party;
import org.example.navigation.NavigationManager;
import org.example.util.AppDialogService;

public class CustomerController extends PartyMasterController implements ScreenLifecycle {
    @Override protected String partyType(){return "CUSTOMER";}
    @Override protected String displayName(){return "Customer";}

    @FXML protected void openCustomer360(){
        if (!org.example.service.PermissionService.allowedSubSection("CUSTOMERS", "360")) {
            AppDialogService.warning(tableParties, "Permission Denied", "Action Not Allowed", "You do not have permission to view Customer 360° Profile.");
            return;
        }
        Party party=tableParties.getSelectionModel().getSelectedItem();
        if(party==null){AppDialogService.warning(tableParties,"Customer 360°","Select a customer","Select a customer before opening Customer 360°.");return;}
        Customer360Context.select(party);
        NavigationManager.navigateOrReport("/fxml/pages/Customer360.fxml");
    }

    @Override
    public void onScreenHidden() {
        super.onScreenHidden();
        CustomerSaleContext.clear();
    }
}
