package com.farmerboy.silageloads

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class AccountActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_account)

        val name = findViewById<EditText>(R.id.accountName)
        val vehicle = findViewById<EditText>(R.id.accountVehicle)
        val truckMode = findViewById<android.widget.CheckBox>(R.id.truckModeCheck)
        val status = findViewById<TextView>(R.id.accountStatus)

        name.setText(Sync.myName(this))
        vehicle.setText(Sync.myVehicle(this))
        truckMode.isChecked = Sync.truckMode(this)

        status.text = if (Sync.configured(this)) {
            "Sharing is on. When you're in a shared job, the crew sees your name, " +
                "vehicle, position while hauling, and the loads you count."
        } else {
            "Sharing isn't set up in this build yet — your name and vehicle are " +
                "saved and will be used as soon as it is."
        }

        findViewById<Button>(R.id.accountSaveBtn).setOnClickListener {
            Sync.saveProfile(this, name.text.toString().trim(), vehicle.text.toString().trim())
            Sync.setTruckMode(this, truckMode.isChecked)
            finish()
        }
    }
}
