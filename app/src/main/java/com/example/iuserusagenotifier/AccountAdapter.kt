package com.example.iuserusagenotifier

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

// Data class representing an account (ensure this is defined somewhere in your project)

// Updated AccountAdapter using the new item layout IDs.
class AccountAdapter(
    private val onAccountSelected: (Account) -> Unit,
    private val onRemoveClicked: (Account) -> Unit
) : ListAdapter<Account, AccountAdapter.AccountViewHolder>(AccountDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AccountViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_account, parent, false)
        return AccountViewHolder(view)
    }

    override fun onBindViewHolder(holder: AccountViewHolder, position: Int) {
        val account = getItem(position)
        holder.bind(account)
    }

    inner class AccountViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        // Using the updated ID "accountName" from your new layout.
        private val accountNameText: TextView = itemView.findViewById(R.id.accountName)
        private val removeButton: MaterialButton = itemView.findViewById(R.id.accountRemoveButton)

        fun bind(account: Account) {
            accountNameText.text = account.username
            // Callback for when the entire item is clicked.
            itemView.setOnClickListener { onAccountSelected(account) }
            // Callback for when the remove button is tapped.
            removeButton.setOnClickListener { onRemoveClicked(account) }
        }
    }
}

class AccountDiffCallback : DiffUtil.ItemCallback<Account>() {
    override fun areItemsTheSame(oldItem: Account, newItem: Account): Boolean {
        // Assuming each account has a unique username.
        return oldItem.username == newItem.username
    }

    override fun areContentsTheSame(oldItem: Account, newItem: Account): Boolean {
        return oldItem == newItem
    }
}
